# mc401
## Overview
MC401 is a protocol agent for Open Remote automation server (https://openremote.io/) to read content of Multical(R) 401 Heater Meter from Kamstrup (https://products.kamstrup.com/index.php). 

The agent connects to the meter over regular serial port supporting RS-232 protocol. To talk with heat meter it requires adjustment of electrical level that is done by two optoisolator - one per each direction to and from the meter. Author successfully tested the isolation with serial ports with:
* mother board (H370HD3 from Gigabyte)
* MosChip standalone controller plugged in to the PCIe slot
* USB attached serial (so called, CDC class), there could be an issue with other models due to voltage levels and current 

For users willing to build their own isolation board schematic and bill of materials is provided.

As the meter requires changing speed between request and response, the Ethernet attached serial port USR-N540 is not supported. Besides that, there was also electrical issues with the USR-N540.

The agent supports basic three requests (numbered 1, 2 and 3) and all fields of them. User needs to provide only unique field name.

From the side of Open Remote server user needs to define serial port used for communication. All fields of request number one are automatically imported to illustrate configuration.

## Deployment
The agent is written in Java and depends on OpenRemote objects and jSerialComm library (https://fazecast.github.io/jSerialComm/). To build it you need to setup custom project as instructed at OpenRemote Wiki page and keep mc401 as submodule. Please use this command to create submodule in your Open Remote project `git submodule add -b master https://github.com/siliconehuntsman/mc401.git mc401/`
Gradle script is provided to build and deploy the agent, the deploy process itself is merely coping output jar to deployment/extensions folder of your Open Remote project. User also needs to download and copy jSerialComm jar file to extensions folder. 

The simplest way to execute the whole flow (compile, build, test and deploy) is to run: `./gradle :mc401` from root of your Open Remote project.

## Test
The MC401 is provided with set of tests that exercise the most of implemented functionalities. So far only elements implemented in mc401protocol class does not have automates tests and was tested "manually" in OpenRemote server. Tests are executed automatically

## Usage
Once server is restarted with jars added, the agent becomes available in OpenRemote. All names given to assets and attributes are for example only and can be selected according to user's desire.
1. Create asset of type agent, name it "mc401_service" and save it
2. Open it and add attribute of MC401 type, name it "WaterHeatMeter" and save again
3. Open asset again, extend created attribute WaterHeatMeter and edit its MetaItem to indicate which serial port you like to use, by default there will be Linus style device name (/dev/ttyS0). Once it is entered, the validity of serial device will be checked, please pay attention at this stage only serial existence is verified. Save the asset, once it saved the protocol will check if there is a Heat Meter installed in this serial port. Corresponding information will be given just below attribute name (WaterHeatMeter)
4. Open asset again, now auto-discovery option should become available
