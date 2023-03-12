package com.dmg.fusion;

import au.com.dmg.fusion.SaleToPOI;
import au.com.dmg.fusion.data.MessageCategory;
import au.com.dmg.fusion.data.MessageType;

public class FusionMessageResponse {
    Boolean isSuccessful;
    MessageType messageType;
    MessageCategory messageCategory;
    SaleToPOI saleToPOI;
    String otherInfo;

    public void setMessage(boolean isSuccessful, MessageType messageType, MessageCategory messageCategory, SaleToPOI saleToPOI, String otherInfo) {
        this.isSuccessful = isSuccessful;
        this.messageType = messageType;
        this.messageCategory = messageCategory;
        this.saleToPOI = saleToPOI;
        this.otherInfo = otherInfo;

    }

    public void setMessage(MessageType messageType, MessageCategory messageCategory, SaleToPOI saleToPOI) {
        this.isSuccessful = true;
        this.messageType = messageType;
        this.messageCategory = messageCategory;
        this.saleToPOI = saleToPOI;
        this.otherInfo = "";

    }

    public void setMessage(boolean isSuccessful, String otherInfo) {
        this.isSuccessful = isSuccessful;
        this.messageType = null;
        this.messageCategory = null;
        this.saleToPOI = null;
        this.otherInfo = otherInfo;

    }}
