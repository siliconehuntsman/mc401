
package net.sfhome.openremote.protocol.mc401;

import net.sfhome.openremote.protocol.*;

import java.time.format.DateTimeFormatter;  
import java.time.LocalDate;   
import java.util.*;
import java.util.AbstractMap.SimpleEntry;
import java.util.function.*;
//import java.util.stream;
import java.util.stream.Collectors;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;

import org.openremote.model.value.*;

public class ResponseRequest extends AbstractResponseRequest {
  
    static List<ResponseField> getDefinedFields(Integer reqNumber) {
        //ArrayList<ResponseField> definedFields = new ArrayList<>();
            
        switch(reqNumber) {
            case 1:
                return Arrays.asList(
                    //Field description based on MultiCal 401 Technical Description by Kalstrump
                    new ResponseField("Energy", "Accumulated energy", ResponseField.FieldType.NUMBER),
                    new ResponseField("Volume", "Volume", ResponseField.FieldType.NUMBER),
                    new ResponseField("Hours", "Hours", ResponseField.FieldType.HOURS),
                    new ResponseField("T1", "Input temperature", ResponseField.FieldType.NUMBER),
                    new ResponseField("T2", "Output temperature", ResponseField.FieldType.NUMBER),
                    new ResponseField("T1_T2", "Temperature difference", ResponseField.FieldType.NUMBER),
                    //The actual heat flow rate can be used as tariff basis (E=1).
                    new ResponseField("Power", "Power", ResponseField.FieldType.NUMBER),
                    //The actual flow can be used as tariff basis (E=2).
                    new ResponseField("Flow", "Flow", ResponseField.FieldType.NUMBER),
                    new ResponseField("PeakPwrFlw", "Peak power/flow actual", ResponseField.FieldType.NUMBER),
                    //Info field gives information about error code of the meter, currently documented values:
                    //000 => Check that the flow direction is correct
                    //004, 008 or 012 - Check temperature sensors, replace if needed
                    //016 - There is air in the flow sensor, release it
                    new ResponseField("Info", "Error code", ResponseField.FieldType.STRING, 7, "%s")
                );
            case 2:
                return Arrays.asList(
                    new ResponseField("CustNo2", "Meter number", ResponseField.FieldType.STRING),
                    new ResponseField("TA2", "Tarrif register 2", ResponseField.FieldType.NUMBER),
                    //Tariff limits are only used when E=1,2,3 or 5 in the DDEFFGG field below
                    //  Tarrif limits determine when tarrif registers need to accumulate energy reading.
                    //  The guess is that depending on the year season two different tariffs are used and
                    //  TLx registers determine month and day of the start of the give tarrif and  
                    //  that given register should record energy consumptions
                    new ResponseField("TL2", "Tariff limit for TA2", ResponseField.FieldType.NUMBER),
                    new ResponseField("TA3", "Tariff register 3", ResponseField.FieldType.NUMBER),
                    new ResponseField("TL3", "Tariff limit for TA3", ResponseField.FieldType.NUMBER),
                    new ResponseField("InA", "Aux water counter (VA input)", ResponseField.FieldType.NUMBER),
                    new ResponseField("InB", "Aux water counter (VB input)", ResponseField.FieldType.NUMBER),
                    //The calculator’s programming number. Determines the flow sensor’s placement in flow or
                    //  return, measuring unit and flow sensor size.
                    //Explanation of fields, values given in brackets are values read from author's meter
                    //  A (3)     - 
                    //  B (2)     -
                    //  CCC (116) - Flow sensor size. E.g. CCC=119 is used with qp 1.5 m3/h.
                    new ResponseField("ABCCC", "Calculator's programming number", ResponseField.FieldType.STRING, 7, "%s"),
                    //The meter’s configuration no. = DD-E-FF-GG indicates display reading, tariff type and input/output.
                    //Explanation of fields, values given in brackets are values read from author's meter
                    //  DD (11) - Display code indicating the display reading selected
                    //  E  (0)  - The required tariff is selected by means of “E”. E.g. E=3 means “cooling tariff”, whereas E=0 means “no tariff”.
                    //  FF (96) - Flow sensor coding of aux water meter (VA). E.g. FF=24 means that a water meter VA is coded for 10 l/pulses.
                    //  GG (00) - Flow sensor coding of aux water meter (VB). E.g. GG=24 means that water meter VB is coded for 10 l/pulses.
                    new ResponseField("DDEFFGG", "Meter configuration no.", ResponseField.FieldType.STRING, 7, "%s"),
                    new ResponseField("Date", "Date", ResponseField.FieldType.DATE) //Date in the format of YYMMDD
                );
            case 3:
                return Arrays.asList(
                    //Request 3 is used to read target data, that is, data stored in the indicated day of the year and used for billing
                    new ResponseField("CustNo3", "Meter number", ResponseField.FieldType.STRING),
                    //Date of the last record in the format of YYMMDD
                    new ResponseField("ReadingDay", "Reading day", ResponseField.FieldType.DATE),
                    new ResponseField("EnergyInPeriod", "Energy consumed within the last period", ResponseField.FieldType.NUMBER),
                    new ResponseField("VolumeInPeriod", "Volume registered for the last period", ResponseField.FieldType.NUMBER),
                    new ResponseField("TA2_Period", "Energy accumulated in time of tariff 2", ResponseField.FieldType.NUMBER),
                    new ResponseField("TA3_Period", "Energy accumulated in time of tariff 3", ResponseField.FieldType.NUMBER),
                    new ResponseField("InAInPeriod", "Aux water counter (VA input) within the last period", ResponseField.FieldType.NUMBER),
                    new ResponseField("InBInPeriod", "Aux water counter (VB input) within the last period", ResponseField.FieldType.NUMBER),
                    new ResponseField("PeakPwrFlwInPeriod", "Peak power/flow within period", ResponseField.FieldType.NUMBER)            
                ); 
              
        }
        return Arrays.asList();
    }
    
    static List<SimpleEntry<Integer, String>> allFields() {
        ArrayList<SimpleEntry<Integer, String>> fields = new ArrayList<>();
        for(int i = 0;i < 4; i++) {
            final int runningReqNumber = i;
            ArrayList<SimpleEntry<Integer, String>> respFields = getDefinedFields(i)
                             .stream()
                             .map(responseField -> new SimpleEntry<Integer, String>(runningReqNumber, responseField.getFieldName()))
                             .collect(Collectors.toCollection(ArrayList::new));
            fields.addAll(respFields);
        }
        return fields;
    }
    
    static int getRequestNumber(String fieldName) {
       Optional<SimpleEntry<Integer, String>> respField = allFields()
           .stream()
           .filter(entry -> fieldName.equalsIgnoreCase(entry.getValue()))
           .findFirst();
       int reqNumber = respField.orElseThrow(() -> new IllegalArgumentException(String.format("This is incorrect field name: %s", fieldName)) ).getKey();     
        
       return reqNumber;
    }
        
    
//    ResponseRequest(Integer requestNumber, Integer requestPeriod, BiConsumer<AttributeRef, Value> attributeUpdater) {
    ResponseRequest(Integer requestNumber, Integer requestPeriod, Consumer<AttributeState> attributeUpdater) {
        super(requestNumber, attributeUpdater);
        this.setRequestPeriod(requestPeriod);
    }

    @Override
    protected void generateFields() {

        fields.addAll(getDefinedFields(this.getRequestNumber()));
        
    }

}