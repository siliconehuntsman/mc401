

package net.sfhome.openremote.protocol.mc401;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;  
import java.time.LocalDate;   
import java.time.Year;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.function.*;

import org.openremote.model.asset.Asset;
import org.openremote.model.asset.AssetAttribute;
import org.openremote.model.asset.AssetDescriptor;
import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.attribute.AttributeValueType;

import org.openremote.model.value.*;

public class ResponseField implements Consumer<String> {

    public enum FieldType {STRING, NUMBER, DATE, TIME, HOURS};
  
    private String name;    //Kamstrup field name
    private String label;   //Nice name
    private Integer length; //Number of charactersd
    private String format;
    private FieldType type;
    private List<AttributeRef> linkedAttributeRefs;
    //private BiConsumer<AttributeRef, Value> attributeUpdater;
    private Consumer<AttributeState> attributeUpdater;
  
   
    public ResponseField(String  name, 
                         String  label,
                         FieldType type,
                         Integer length,
                         String  format
                         ) {
      this.name = name;
      this.label = label;
      this.length = length;
      this.type = type;
      this.format = format;        
        
      linkedAttributeRefs = Collections.synchronizedList(new ArrayList<>());
    }
    public ResponseField(String  name, 
                         String  label,
                         FieldType type
                         ) {
      this(name, label, type, 0, "");   
      Integer length;
      String format;
      
      switch(type) {
          case NUMBER: 
              length = 7;
              format = "%5.2s";
              break;
          case STRING:
              length = 11;
              format = "%s";
              break;
          case DATE:
              length = 7;
              format = "yyyy-mm-dd"; //System format is used
              break;
          case TIME:
              length = 7;
              format = "%2s:%2s";
              break;
          case HOURS:
              length = 7;
              format = "%7s";
              break;
                  
          default: //It should be ThrowException
              length = 0;
              format = "";
              break;
      }
      this.length = length;
      this.format = format;   
    }
    
    //Propagate new value to all linked attributes
    private void updateLinkedAttributes(Value newValue) {       
        
        // Look for any attributes that stores thos filed of response
        synchronized (linkedAttributeRefs) {
            Value finalValue = newValue;
            linkedAttributeRefs.forEach(ref -> {
                //System.out.printf("Calling updater\n");
                //this.attributeUpdater.accept(ref, finalValue);
                AttributeState state = new AttributeState(ref, finalValue);
                this.attributeUpdater.accept(state);
                });
        }
    }    
          
    public String getFieldName() {
      return name;
    }
  
    public String getLabel() {
      return label;
    }
  
    public Integer getLength() {
      return length; 
    }
  
    public String getFormat() {
      return format; 
    }
    
    public AttributeValueType getType() {
        AttributeValueType  valueType;
        switch(type) {
          case NUMBER: 
              valueType = AttributeValueType.NUMBER;
              break;
          case STRING:
              valueType = AttributeValueType.STRING;
              break;
          case DATE:
              valueType = AttributeValueType.TIMESTAMP;
              break;
          case TIME:
              valueType = AttributeValueType.TIMESTAMP;
              break;
          case HOURS:
              valueType = AttributeValueType.DURATION;
              break;
                  
          default: //It should be ThrowException
              valueType = AttributeValueType.STRING;
              break;
      }
      return valueType; 
    }

    
    //Function of Consumer to be called when MC401Port process response from meter 
    //It accepts value received from meter, process it according to format settings and propagates value to linked attrbutes
    //Value is not stored within the class, class keeps inly information about type and format of field
    public synchronized void accept(String value) {
        //System.out.println(String.format("Field name %s processing: %s", getFieldName(), value));
        Value newValue;
        switch(type) {
            case NUMBER:                 
                BigDecimal bgConv = BigDecimal.valueOf(Long.parseLong(value), 2);
                newValue = Values.create(bgConv.doubleValue());
                break;
            case STRING:
                String sConv = String.format(this.format, value);
                newValue = Values.create(sConv);
                break;
            case DATE:
                int hundyears = Year.now().getValue() / 100;
                LocalDate readoutDate = LocalDate.of(hundyears*100 + Integer.parseInt(value.substring(1,3)), 
                                                   Integer.parseInt(value.substring(3,5)),
                                                   Integer.parseInt(value.substring(5,7)));
                //DateTimeFormatter dateFromat = DateTimeFormatter.ofPattern(this.Format);  
                String date = readoutDate.toString();
                newValue = Values.create(date);
                break;
            case HOURS:
                //String converted = String.format(this.format, value); 
                Long  lConv = Long.parseLong(value);
                newValue = Values.create(lConv.doubleValue());
                break;
            default: //throw 
                newValue = Values.create(value);    
                break;
         }       
        //System.out.printf(" value: %s\n", value);
        this.updateLinkedAttributes(newValue);
    }
  


    // -----------------------------------------
    // Attribute handling methods
    // -----------------------------------------
    public Integer addLinkedAttribute(AttributeRef attributeRef) throws IllegalArgumentException {
    
        //System.out.printf("Adding linked attribute for field: %s\n", this.getFieldName());
        if(attributeRef == null) {
            new IllegalArgumentException("AttributeRef cannot be null");
        }
        linkedAttributeRefs.add(attributeRef);
        return linkedAttributeRefs.size();
    }
    public Integer removeLinkedAttribute(AttributeRef attributeRef) throws IllegalArgumentException {
        if(attributeRef == null) {
             new IllegalArgumentException("AttributeRef cannot be null");
        }
        linkedAttributeRefs.removeIf(ref -> ref == attributeRef);
        return linkedAttributeRefs.size();
    }    
    /*
    public void setUpdater(BiConsumer<AttributeRef, Value> attributeUpdater) {
        this.attributeUpdater = attributeUpdater;
    }
    */
    public void setUpdater(Consumer<AttributeState> attributeUpdater) {
        this.attributeUpdater = attributeUpdater;  
        
    }
    
    public Integer peepNumOfAttr() {
        //System.out.printf("There are %d attributes registered for field name: %s\n", linkedAttributeRefs.size(), this.getFieldName());
        return linkedAttributeRefs.size();
    }

}