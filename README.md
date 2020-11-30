# mc401
## Overview
MC401 is a protocol agent for Open Remote v3 automation server (https://openremote.io/) to read content of Multical(R) 401 Heater Meter from Kamstrup (https://products.kamstrup.com/index.php). 

The agent connects to the meter over regular serial port supporting RS-232 protocol. To talk with heat meter it requires adjustment of electrical level that is done by two optoisolator - one per each direction to and from the meter. Author successfully tested the isolation with serial ports with:
* mother board (H370HD3 from Gigabyte)
* MosChip standalone controller plugged in to the PCIe slot
* USB attached serial (so called, CDC class), there could be an issue with other models due to voltage levels and current 

As the meter requires changing speed between request and response, the Ethernet attached serial port USR-N540 is not supported. Besides that, there was also electrical issues with the USR-N540.

For users willing to build their own isolation board I provide schematic and bill of materials (TODO). If someone wants to build its own board and has problem to find specific transoptor then please select one with lowest forward current.

The agent supports basic three requests (numbered 1, 2 and 3) and all fields of them. User needs to provide only unique field name.

From the side of Open Remote server user needs to define serial port used for communication. All fields of request number one are automatically imported to illustrate configuration. 

## Deployment
The agent is written in Java and depends on OpenRemote objects and jSerialComm library (https://fazecast.github.io/jSerialComm/). To build it you need to setup custom project as instructed at OpenRemote Wiki page and keep mc401 as submodule. Please use this command to create submodule in your Open Remote project `git submodule add -b master https://github.com/siliconehuntsman/mc401.git mc401/`
Gradle script is provided to build and deploy the agent, the deployment process itself is merely coping output jar to deployment/extensions folder of your Open Remote project. User also needs to download and copy jSerialComm jar file to extensions folder. Copying of output jar should happen automatically.

The simplest way to execute the whole flow (compile, build, test and deploy) is to run: `./gradle :mc401:installDist` from root of your Open Remote project.  

Please remember that the port used for communication in OpenRemote needs to be first assigned to manager container in docker compose file. It could look like the following excerpt:
``` 
manager:
   extends:
      file: openremote/profile/deploy.yml
 [...]
    devices:
      #MC401
      - /dev/ttyS0:/dev/ttyS0
```    

## Tests
The MC401 is provided with set of tests that exercise the most of implemented functionalities outside of OpenRemote server environment. So far only elements implemented in MC401protocol class do not have automated tests and were tested "manually" in OpenRemote server. Provided tests are executed automatically when project is build. Project contain static MC401Debug class with single static property ON. It should be set to true for debug and false for release run. When this property is false it disables some internal functions required to conduct invasive test.
Please note that many tests depend on physical MC401 heat meter connected to serial port, they will fail if heat meter does not respond to requests. For the time being all tests use hard coded name of serial device (/dev/ttyS0).

## Usage
Once server is restarted with jars added, the agent becomes available in OpenRemote. Below you will find short installation guide (all names are given for illustration only).
1. Create asset of type agent, name it "mc_serv" and save it
2. Open created asset and add attribute of MC401 type, name it "WaterHeatMeter" and save again
3. Open asset again, extend created attribute WaterHeatMeter and edit its configuration (Attribute configuration) to indicate which serial port you like to use, by default there will be Linux style device name given – /dev/ttyS0. Once it is entered, the validity of serial device will be checked (seems not to work in the last version of the server), please pay attention at this stage only serial existence is verified; Save the asset
4. Once it saved, the protocol will check if there is a Heat Meter installed on this serial port. Corresponding information will be given just below attribute name (WaterHeatMeter), protocol status will change from CONNECTING to CONNECTED and color of the attribute bar will change from yellow to green.
If you change configuration in running system it could take a moment to change status due to process of emptying internal queues of running agent
5. Open asset again, now auto-discovery option should become available. Please click “Select asset” and select in the left pane location where your heat meter shall be instantiated, click “Ok” and “Discover assets” buttons. In response new asset named “Heat meter” will be created, it will contain all fields of request number 1 populated. Please note that “Upload & import links from file” button does not work.
6. From now on the agent will send on regular basis request number 1 to heat meter device to fetch updated values. User can add additional fields to Heat meter asset to fetch other values, as field names are unique the agent will recognize which request needs to be send to get its value from the heat meter device.

### Protocol parameters
MC401 agent has couple of configuration items to tweak given instance behavior. They are:
* DEVname – serial name with heat meter connected
* DTRused – whether DTR signal of serial port shall be controlled by this instance of MC401
* DTRvalue – desired value of DTR line
* RTSused – whether RTS signal of serial port shall be controlled by this instance of MC401
* RTSvalue – desired value of RTS line
* REQ1period – time between sending request number 1 in minutes
* REQ2period – time between sending request number 2 in minutes
* REQ3period – time between sending request number 3 in minutes

The default values of 
* DTR and RTS items are selected according to author's optoisolator requirements.
* REQxperiod items are 1440 minutes which corresponds to a single read-out per day, it shall be far enough for most systems as MC401 heat meter read out values do not change too often. 

### Field names
The MC401 understands the following field names (in bold). The names and descriptions are based on fields defined in MultiCal 401 Technical Description by Kalstrump
1. Response field to request number 1:
   * **Energy** - Accumulated energy
   * **Volume** - Volume
   * **Hours** - Hours
   * **T1** - Input temperature
   * **T2** - Output temperature
   * **T1_T2** - Temperature difference
   * **Power** - Power
   * **Flow** - Flow
   * **PeakPwrFlw** - Peak power/flow actual
   * **Info** - Error code (Please check heat meter documentation for details)
     
2. Response field to request number 2:
   * **CustNo2** - Meter number
   * **TA2** - Tariff register 2; Tariff limits are only used when E=1,2,3 or 5 in the DDEFFGG field below
            Tariff limits determine when tariff registers need to accumulate energy reading.
            The guess is that depending on the year season two different tariffs are used and
            TLx registers determine month and day of the start of the give tariff and  
            that given register should record energy consumption
   * **TL2** - Tariff limit for TA2
   * **TA3** - Tariff register 3
   * **TL3** - Tariff limit for TA3
   * **InA** - Aux water counter (VA input)
   * **InB** - Aux water counter (VB input)
   * **DDEFFGG** - Meter configuration no. (Please check heat meter documentation for details)
   * **ABCCC** - Calculator's programming number (Please check heat meter documentation for details)        
   * **Date** - Date
    
3. Response field to request number 3: Request 3 is used to read target data, that is, data stored in the indicated day of the year and used for billing
   * **CustNo3** - Meter number (equals to CustNo2)
   * **ReadingDay** - Reading day
   * **EnergyInPeriod** - Energy consumed within the last period
   * **VolumeInPeriod** - Volume registered for the last period
   * **TA2_Period** - Energy accumulated in time of tariff 2
   * **TA3_Period** - Energy accumulated in time of tariff 3
   * **InAInPeriod** - Aux water counter (VA input) within the last period
   * **InBInPeriod** - Aux water counter (VB input) within the last period
   * **PeakPwrFlwInPeriod** - Peak power/flow within period
