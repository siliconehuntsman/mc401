

package net.sfhome.openremote.protocol.mc401;

import java.util.*;

import org.openremote.model.value.*;


public class ResponseMessage {
    private Integer reqNumber;
    private String  response;
    
    public ResponseMessage() {
        this.reqNumber = 0;
        this.response = response;
                
    }

    
    public ResponseMessage(Integer reqNumber, String response) {
        this.reqNumber = new Integer(reqNumber);
        this.response = new String(response);
                
    }
    public Integer getRequestNumber() {
        return this.reqNumber;
    }
    
    public String getResponse() {
        return this.response;
    }
    
    public boolean isDeathPill() {
        return this.reqNumber == 0 ? true : false;
    }
    
}