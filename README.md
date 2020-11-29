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

The simplest way to execute the whole flow (compile, build, test and deploy) is to run: `./gradle :mc401` from root of your Open Remote project.

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
The MC401 is provided with set of tests that exercise the most of implemented functionalities outside of OpenRemote server environment. So far only elements implemented in MC401protocol class do not have automated tests and were tested "manually" in OpenRemote server. Provided tests are executed automatically when project is build. Project contain static MC401Debug class with single static property ON. It should be set to true for debug and false for release run. This property is set to false disables some internal functions required to conduct invasive test.


## Usage
Once server is restarted with jars added, the agent becomes available in OpenRemote. All names given to assets and attributes are for example only and can be selected according to user's desire.
1. Create asset of type agent, name it "mc401_service" and save it
2. Open it and add attribute of MC401 type, name it "WaterHeatMeter" and save again
3. Open asset again, extend created attribute WaterHeatMeter and edit its MetaItem to indicate which serial port you like to use, by default there will be Linus style device name (/dev/ttyS0). Once it is entered, the validity of serial device will be checked, please pay attention at this stage only serial existence is verified. Save the asset, once it saved the protocol will check if there is a Heat Meter installed in this serial port. Corresponding information will be given just below attribute name (WaterHeatMeter)
4. Open asset again, now auto-discovery option should become available

## Field names
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
   * **Info** - Error code, Info field gives information about error code of the meter, currently documented values:
     - 000 => Check that the flow direction is correct
     - 004, 008 or 012 - Check temperature sensors, replace if needed
     - 016 - There is air in the flow sensor, release it
     
2. Response field to equest number 2:
   * **CustNo2** - Meter number
   * **TA2** - Tarrif register 2; Tariff limits are only used when E=1,2,3 or 5 in the DDEFFGG field below
            Tarrif limits determine when tarrif registers need to accumulate energy reading.
            The guess is that depending on the year season two different tariffs are used and
            TLx registers determine month and day of the start of the give tarrif and  
            that given register should record energy consumptions
   * **TL2** - Tariff limit for TA2
   * **TA3** - Tariff register 3
   * **TL3** - Tariff limit for TA3
   * **InA** - Aux water counter (VA input)
   * **InB** - Aux water counter (VB input)
   * **DDEFFGG** - Meter configuration no.
        The calculator’s programming number. Determines the flow sensor’s placement in flow or
          return, measuring unit and flow sensor size.
        Explanation of fields, values given in brackets are values read from author's meter
         - A (3)     - 
         - B (2)     -
         - CCC (116) - Flow sensor size. E.g. CCC=119 is used with qp 1.5 m3/h.
   * **ABCCC** - Calculator's programming number
        The meter’s configuration no. = DD-E-FF-GG indicates display reading, tariff type and input/output.
        Explanation of fields, values given in brackets are values read from author's meter
     - DD (11) - Display code indicating the display reading selected
     - E  (0)  - The required tariff is selected by means of “E”. E.g. E=3 means “cooling tariff”, whereas E=0 means “no tariff”.
     - FF (96) - Flow sensor coding of aux water meter (VA). E.g. FF=24 means that a water meter VA is coded for 10 l/pulses.
     - GG (00) - Flow sensor coding of aux water meter (VB). E.g. GG=24 means that water meter VB is coded for 10 l/pulses.
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

