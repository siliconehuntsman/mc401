/*
 * This Java source file was generated by the Gradle 'init' task.
 */
package net.sfhome.openremote.protocol.mc401.TestUtils;

import com.google.gwt.regexp.shared.RegExp;
//import org.gwtproject.regexp.shared.RegExp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.Exception;
import java.util.AbstractMap.SimpleEntry;
import java.util.Collections;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.*;
import java.util.stream.*;
import java.util.stream.Collectors.*;


//import org.junit.Test;
//port org.junit.BeforeEach;

//import static org.junit.Assert.*;


import net.sfhome.openremote.protocol.mc401.*;

import org.openremote.model.attribute.AttributeRef;
import org.openremote.model.attribute.AttributeState;
import org.openremote.model.value.*;


/**
 * ScoreBoard is supposed to receive data sent for processing (response) and the data processed (attribute updates) and compare them for equality.
 * This class can be used to  check if data was correctly received.
 *
 */
public class ScoreBoard {
    //Database
    private ArrayList<SimpleEntry<Integer, TreeMap<String, String>>> responseDb;
                          //ReqNumber        FieldName, FieldValue

    private TreeMap<String, String> activeResponse = null;
    private Integer activeResponseNumber = new Integer(0);
    private Integer activeField = 0;
    private long errorCounter = 0;

    public ScoreBoard() {
        responseDb = new ArrayList<>();
    }


    /**
     * Method adds subsequent field to active response entry
     * @param value - value of the field
     * @return The same value that was passed as parameter. It gives eases of use.
     */
    public String addResponseField(String value) {
        assertNotNull(activeResponse, "ScoreBoard.addResponseField: activeResponse cannot be NULL");

        activeResponse.put(ResponseFields.getResponseFields(activeResponseNumber)[activeField++], value);

        return value;
    }

    /**
     * Method stores activeResponse (if not null) in the database along with request/response number and creates a new clean entry to be filled in
     * @param respNumber - request/response number
     */
    public void setActiveResponseNumber(Integer respNumber) {
        assertTrue(respNumber > 0 && respNumber < 4, String.format("ScoreBoard.setActiveResponseNumber: respNumber argument needs to be within <1,3> range, while it is %d", respNumber));

        if( activeResponse != null) {
            SimpleEntry<Integer, TreeMap<String, String>> entry = new SimpleEntry<>(activeResponseNumber, activeResponse);
            responseDb.add(entry);
        }

        activeResponse = new TreeMap<>();               

        activeResponseNumber = respNumber;
        activeField = 0;            

    }

    /**
     * Method stores activeResponse (if not null) in the database along with request/response number
     * 
     */
    public void closeActiveResponse() {

        if(activeResponse != null) {
            SimpleEntry<Integer, TreeMap<String, String>> entry = new SimpleEntry<>(activeResponseNumber, activeResponse);
            responseDb.add(entry);
        }

        activeResponse = null;               

        activeResponseNumber = 0;
        activeField = 0;            

    }

    /**
     * Method finds the youngest entry either in the database or activeResponse with given fieldName
     * @param fieldName - name of the field to be found
     * @return the while entry
     */
    private SimpleEntry<Integer, TreeMap<String, String>> findLastEntry(String fieldName) {

        Integer respNumber = ResponseFields.findResponseNumber(fieldName);

        SimpleEntry<Integer, TreeMap<String, String>> entry;
        //Does number of activeResponse equal request/response number of given field
        if(activeResponseNumber == respNumber && activeResponse != null) {
            //Yes, use activeResponse as a "found" entry 
            entry = new SimpleEntry<>(activeResponseNumber, activeResponse);
            System.out.println(String.format("active resposne used: %d - %s", activeResponseNumber, activeResponse));
        } else {
            //No, we need to find entry in response database (responseDb)
            Supplier<Stream<SimpleEntry<Integer, TreeMap<String, String>>>> respSupp = () -> 
                responseDb.stream().filter(element -> element.getKey() == respNumber);
            long count = respSupp.get().count();                               //    ^PRAWDOPODOBNIE zła konwersja do stream
            entry = respSupp.get().skip(count - 1).findFirst().get();
        }                
        assertNotNull(entry, String.format("ScoreBoard.findLastEntry: entry for field named: '%s' not found!!", fieldName));
        return entry;
    }

    public void addAtributeValue(String entityId, String fieldName, String value) {
        SimpleEntry<Integer, TreeMap<String, String>> entry = findLastEntry(fieldName);
        //TODO recording attribute update value
        Integer reqNumber = entry.getKey(); //request number
        String fieldValue = entry.getValue().get(fieldName);

        Long fieldLong = Long.parseLong(fieldValue);
        int fieldLength = fieldValue.length();
        String fieldDecimal;
        if((fieldLong %100) %10 == 0 )
            fieldDecimal = String.format("%d.%-1d", fieldLong / 100, (fieldLong %100)/10);
        else
            fieldDecimal = String.format("%d.%02d", fieldLong / 100, (fieldLong %100));

        String[] stringFields = {"CustNo2", "CustNo3", "ABCCC", "DDEFFGG", "Date"};
        if(fieldName.equals("CustNo2") || fieldName.equals("CustNo3") || fieldName.equals("ABCCC") ||
           fieldName.equals("DDEFFGG") || fieldName.equals("Info")) {
           if(!fieldValue.equals(value)) {
               System.out.println(String.format("Field (%s) values are not equal - attribute: %s vs. response: %s !!", fieldName, value, fieldValue));
               errorCounter++;
           }
        }
        else if(fieldName.equals("Date") || fieldName.equals("ReadingDay")) {
            String fieldDate = String.format("20%s-%s-%s", fieldValue.substring(1,3), fieldValue.substring(3,5), fieldValue.substring(5,7));
           if(!value.equals(fieldDate)) {
               System.out.println(String.format("Field (%s) values are not equal - attribute: %s vs. response: %s (DateString: %s) !!", fieldName, value, fieldValue, fieldDate));
               errorCounter++;
           }
        }
        else if(fieldName.equals("Hours")) {
            if(!value.equals(String.format("%d.0", fieldLong))) {
               System.out.println(String.format("Field (%s) values are not equal - attribute: %s vs. response: %s (Long.0: %s) !!", fieldName, value, fieldValue, String.format("%.1d", fieldLong)));
               errorCounter++;
           }
        }
        else if(!value.equals(fieldDecimal)) {
            System.out.println(String.format("Field (%s) values are not equal - attribute: %s vs. response: %s (DecimalString: %s) !!", 
                                             fieldName, value, fieldValue, fieldDecimal));
            errorCounter++;
        }
    }

    public long getErrorCounter() {
        return errorCounter;
    }
}



            
