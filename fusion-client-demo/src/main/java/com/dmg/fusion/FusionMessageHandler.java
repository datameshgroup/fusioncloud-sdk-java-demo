package com.dmg.fusion;

import au.com.dmg.fusion.data.MessageCategory;
import au.com.dmg.fusion.data.MessageType;
import au.com.dmg.fusion.request.SaleToPOIRequest;
import au.com.dmg.fusion.request.displayrequest.DisplayRequest;
import au.com.dmg.fusion.response.EventNotification;
import au.com.dmg.fusion.response.SaleToPOIResponse;

import java.text.SimpleDateFormat;
import java.util.Date;

public class FusionMessageHandler {
    private static String TAG = "FusionMessageHandler";
    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    FusionMessageResponse fusionMessageResponse = new FusionMessageResponse();

    MessageCategory messageCategory;

    public FusionMessageResponse handle(
            SaleToPOIRequest request
    ){
        log("Start SaleToPOIRequest");
        log(String.format("Request(JSON): %s", request.toJson()));
        if(request.getMessageHeader()==null){
            fusionMessageResponse.setMessage(false,"Invalid Message");
            return fusionMessageResponse;
        }
        messageCategory = request.getMessageHeader().getMessageCategory();
        if (messageCategory == MessageCategory.Display) {
            DisplayRequest displayRequest = request.getDisplayRequest();
            if (displayRequest != null) {
                log("Display Output = " + displayRequest.getDisplayText());
                fusionMessageResponse.setMessage(true, MessageType.Request, MessageCategory.Display, null, "");
                return fusionMessageResponse;
            }
        }

        log("End SaleToPOIRequest");
        fusionMessageResponse.setMessage(false,"Unknown Error"); //TODO Check validation
        return fusionMessageResponse;
    }


    public FusionMessageResponse handle(
            SaleToPOIResponse response
    ) {
        log("Start SaleToPOIResponse");
        log(String.format("Response(JSON): %s", response.toJson()));

        messageCategory = response.getMessageHeader().getMessageCategory();
        log("Message Category: " + messageCategory);

        switch (messageCategory) {
            case Event:
                EventNotification eventNotification = response.getEventNotification();
                log("Event Details: " + eventNotification.getEventDetails());
                fusionMessageResponse.setMessage(MessageType.Response, MessageCategory.Event, response); //TODO successful but ignore?
                break;
            case Login:
                fusionMessageResponse.setMessage(MessageType.Response, MessageCategory.Login, response); //TODO successful but ignore?
                break;
            case Payment:
                fusionMessageResponse.setMessage(MessageType.Response, MessageCategory.Payment, response);
                break;
            case TransactionStatus:
                fusionMessageResponse.setMessage(MessageType.Response, MessageCategory.TransactionStatus, response);
                break;
        }

        log("End SaleToPOIResponse");

        return fusionMessageResponse;
    }


    private void log(String logData) {
        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + ": " + TAG + ": " + logData); // 2021.03.24.16.34.26
    }

}


