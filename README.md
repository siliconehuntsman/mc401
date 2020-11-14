# mc401

MC401 is a protocol agent for Open Remote automation server (https://openremote.io/) to read content of Multical(R) 401 Heater Meter from Kamstrup. 

The agent connects to the meter over regular serial port supporting RS-232 protocol. To talk with heat meter it requires adjustment of electrical level that is done by two optoisolator - one per each direction to and from the meter. Author successfully tested the isolation with serial ports with:
* mother board (H370HD3 from Gigabyte)
* MosChip standalone controller plugged in to the PCIe slot
* USB attached serial (so called, CDC class), there could be an issue with other models due to voltage levels and current 

For users willing to build their own isolation board schematic and bill of materials is provided.

As the meter requires changing speed between request and response, the Ethernet attached serial port USR-N540 is not supported. Besides that, there was also electrical issues with the USR-N540.

The agent supports basic three requests (numbered 1, 2 and 3) and all fields of them. User needs to provide only unique field name.

From the side of Open Remote server user needs to define serial port used for communication. All fields of request number one are automatically imported to illustrate configuration.
