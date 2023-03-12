package com.dmg.fusion;

import au.com.dmg.fusion.MessageHeader;
import au.com.dmg.fusion.SaleToPOI;
import au.com.dmg.fusion.client.FusionClient;
import au.com.dmg.fusion.data.*;
import au.com.dmg.fusion.exception.FusionException;
import au.com.dmg.fusion.request.Request;
import au.com.dmg.fusion.request.SaleTerminalData;
import au.com.dmg.fusion.request.SaleToPOIRequest;
import au.com.dmg.fusion.request.aborttransactionrequest.AbortTransactionRequest;
import au.com.dmg.fusion.request.loginrequest.LoginRequest;
import au.com.dmg.fusion.request.loginrequest.SaleSoftware;
import au.com.dmg.fusion.request.paymentrequest.*;
import au.com.dmg.fusion.request.transactionstatusrequest.MessageReference;
import au.com.dmg.fusion.request.transactionstatusrequest.TransactionStatusRequest;
import au.com.dmg.fusion.response.EventNotification;
import au.com.dmg.fusion.response.Response;
import au.com.dmg.fusion.response.ResponseResult;
import au.com.dmg.fusion.response.SaleToPOIResponse;
import au.com.dmg.fusion.securitytrailer.SecurityTrailer;
import au.com.dmg.fusion.util.MessageHeaderUtil;
import au.com.dmg.fusion.util.SecurityTrailerUtil;

import javax.naming.ConfigurationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.TimeUnit;

public class FusionTransactionProcessor {
    private FusionClient fusionClient;

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    String mockHostProductCode = ""; //Update this for Mock Host Testing
    String providerIdentification = "Company A"; // test environment only - replace for production
    String applicationName = "POS Retail"; // test environment only - replace for production
    String softwareVersion = "01.00.00"; // test environment only - replace for production
    String certificationCode = "98cf9dfc-0db7-4a92-8b8cb66d4d2d7169"; // test environment only - replace for production
    private String saleID;
    private String poiID;
    private String kek;
    boolean useTestEnvironment = true;

    //Timer settings; Update as needed.
    long milliMultiplier = 1000;
    long loginTimeout = milliMultiplier * 60;
    long paymentTimeout = milliMultiplier * 60; //60
    long errorHandlingTimeout = milliMultiplier * 90; //90
    long prevTime;

    boolean waitingForResponse;
    int secondsRemaining;

    MessageCategory currentTransaction = MessageCategory.Login; // TODO: currentTransaction validation
    String currentServiceID;
    String referenceServiceID;

    void Listen () {
            // Print seconds remaining
            prevTime = printSecondsRemaining(currentTransaction.toString(), prevTime);

            FusionMessageHandler fmh = new FusionMessageHandler();

            SaleToPOI saleToPOI;
            saleToPOI = fusionClient.readMessage();

            if(saleToPOI==null){
                return;
            }
            if(saleToPOI instanceof SaleToPOIRequest ) {
                //Reset timeout (Not applicable to transaction status)
                FusionMessageResponse fmr = fmh.handle((SaleToPOIRequest) saleToPOI);
                if(currentTransaction.equals(MessageCategory.Payment)){
                    secondsRemaining = (int) (paymentTimeout/1000); //Converting to seconds
                }
                waitingForResponse=true;

            }
            if (saleToPOI instanceof SaleToPOIResponse) {
                FusionMessageResponse fmr = fmh.handle((SaleToPOIResponse) saleToPOI);
                waitingForResponse=false;
                switch (fmr.messageCategory){
                    case Event:
                        SaleToPOIResponse spr = (SaleToPOIResponse) fmr.saleToPOI;
                        EventNotification eventNotification = spr.getEventNotification();
                        log("Event Details: " + eventNotification.getEventDetails());
                        break;
                    case Login:
                        handleLoginResponseMessage((SaleToPOIResponse) fmr.saleToPOI);
                        break;
                    case Payment:
                        handlePaymentResponseMessage((SaleToPOIResponse)fmr.saleToPOI);
                        break;
                    case TransactionStatus:
                        handleTransactionResponseMessage((SaleToPOIResponse)fmr.saleToPOI);
                        break;
                }
            }

    }

    public FusionTransactionProcessor() {
        //these config values need to be configurable in POS
        saleID = ""; // Replace with your test SaleId provided by DataMesh
        poiID = ""; // Replace with your test POIID provided by DataMesh

        fusionClient = new FusionClient(useTestEnvironment); //need to override this in production
        kek = "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21"; //for dev only, need to be replaced with prod value in prod
        fusionClient.setSettings(saleID, poiID, kek); // replace with the Sale ID provided by DataMesh
    }

    public void initiatePaymentTransaction() {
        try {
            fusionClient.connect();

            if(doLogin()) {
                doPayment();
            }

            fusionClient.disconnect();
            log("Disconnected from websocket server");
        } catch (FusionException | IOException e) {
            log(e);
        }
    }

    private boolean doLogin() {
        currentServiceID = MessageHeaderUtil.generateServiceID();
        try {
            SaleToPOIRequest loginRequest = buildLoginRequest(currentServiceID);
            log("Sending message to websocket server: " + "\n" + loginRequest);
            fusionClient.sendMessage(loginRequest);

            //Set timeout
            prevTime = System.currentTimeMillis();
            secondsRemaining = (int) (loginTimeout/1000);

            // Loop for Listener
            waitingForResponse = true;
            while(waitingForResponse) {
                if(secondsRemaining<1) {
                    log("Login Request Timeout...", true);
                    break;
                }
                Listen();
            }
        } catch (ConfigurationException e) {
            log(e);
        }
        return true;
    }

    private void doPayment() {
        currentServiceID = MessageHeaderUtil.generateServiceID();

        //Preparing for Transaction Status Check
        String abortReason = "";

        try {
            SaleToPOIRequest paymentRequest = buildPaymentRequest(currentServiceID);
            log("Sending message to websocket server: " + "\n" + paymentRequest);
            fusionClient.sendMessage(paymentRequest);
            currentTransaction = MessageCategory.Payment;

            // Set timeout
            prevTime = System.currentTimeMillis();
            secondsRemaining = (int) (paymentTimeout/1000);

            waitingForResponse = true;
            while(waitingForResponse) {
                if(secondsRemaining < 1) {
                    abortReason = "Timeout";
                    log("Payment Request Timeout...", true);
                    checkTransactionStatus(currentServiceID, abortReason);
                    break;
                }
                Listen();
            }
        } catch (ConfigurationException e) {
            log(String.format("Exception: %s", e.toString()), true);
            abortReason = "Other Exception";
            checkTransactionStatus(currentServiceID, abortReason);
        }
    }

    private void checkTransactionStatus(String serviceID, String abortReason) {
        try {
            if (abortReason != "") {
                SaleToPOIRequest abortTransactionPOIRequest = buildAbortRequest(serviceID, abortReason);

                log("Sending abort message to websocket server: " + "\n" + abortTransactionPOIRequest);
                fusionClient.sendMessage(abortTransactionPOIRequest);
            }


            SaleToPOIRequest transactionStatusRequest = buildTransactionStatusRequest(serviceID);

            log("Sending transaction status request to check status of payment...");

            fusionClient.sendMessage(transactionStatusRequest);
            currentTransaction = MessageCategory.TransactionStatus;

            // Set timeout
            prevTime = System.currentTimeMillis();
            secondsRemaining = (int) (errorHandlingTimeout/1000);

            waitingForResponse = true;
            while(waitingForResponse) {
                if(secondsRemaining < 1) {
                    log("Transaction Status Request Timeout...");
                    log("Display on POS = Please check Satellite Transaction History", true);
                    break;
                }
                Listen();
            }
            } catch (ConfigurationException e) {
                throw new RuntimeException(e);
            }
    }

    private SaleToPOIRequest buildLoginRequest(String serviceID) throws ConfigurationException {
        // Login Request
        SaleSoftware saleSoftware = new SaleSoftware.Builder()//
                .providerIdentification(providerIdentification)//
                .applicationName(applicationName)//
                .softwareVersion(softwareVersion)//
                .certificationCode(certificationCode)//
                .build();

        SaleTerminalData saleTerminalData = new SaleTerminalData.Builder()//
                .terminalEnvironment(TerminalEnvironment.SemiAttended)//
                .saleCapabilities(Arrays.asList(SaleCapability.CashierStatus, SaleCapability.CustomerAssistance,
                        SaleCapability.PrinterReceipt))//
                .build();

        LoginRequest loginRequest = new LoginRequest.Builder()//
                .dateTime(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date()).toString())//
                .saleSoftware(saleSoftware)//
                .saleTerminalData(saleTerminalData)//
                .operatorLanguage("en")//
                .build();

        // Message Header
        MessageHeader messageHeader = new MessageHeader.Builder()//
                .messageClass(MessageClass.Service)//
                .messageCategory(MessageCategory.Login)//
                .messageType(MessageType.Request)//
                .serviceID(serviceID)//
                .saleID(saleID)//
                .POIID(poiID)//
                .build();

        SecurityTrailer securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, loginRequest, useTestEnvironment);


        SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
                .messageHeader(messageHeader)//
                .request(loginRequest)//
                .securityTrailer(securityTrailer)//
                .build();

        return saleToPOI;
    }

    private  SaleToPOIRequest buildPaymentRequest(String serviceID) throws ConfigurationException {
        // Payment Request
        SaleTransactionID saleTransactionID = new SaleTransactionID.Builder()//
                .transactionID("transactionID" + new SimpleDateFormat("HH:mm:ssXXX").format(new Date()).toString())//
                .timestamp(Instant.now()).build();

        SaleData saleData = new SaleData.Builder()//
                // .operatorID("")//
                .operatorLanguage("en")//
                .saleTransactionID(saleTransactionID)//
                .build();

        AmountsReq amountsReq = new AmountsReq.Builder()//
                .currency("AUD")//
                .requestedAmount(new BigDecimal(1000.00))//
                .build();

        SaleItem saleItem = new SaleItem.Builder()//
                .itemID(0)//
                .productCode(mockHostProductCode)//
                .unitOfMeasure(UnitOfMeasure.Other)//
                .quantity(new BigDecimal(1))//
                .unitPrice(new BigDecimal(100.00))//
                .itemAmount(new BigDecimal(100.00))//
                .productLabel("Product Label")//
                .build();

        PaymentInstrumentData paymentInstrumentData = new PaymentInstrumentData.Builder()//
                .paymentInstrumentType(PaymentInstrumentType.Cash)//
                .build();

        PaymentData paymentData = new PaymentData.Builder()//
                .paymentType(PaymentType.Normal)//
                .paymentInstrumentData(paymentInstrumentData)//
                .build();

        PaymentTransaction paymentTransaction = new PaymentTransaction.Builder()//
                .amountsReq(amountsReq)//
                .addSaleItem(saleItem)//
                .build();

        PaymentRequest paymentRequest = new PaymentRequest.Builder()//
                .paymentTransaction(paymentTransaction)//
                .paymentData(paymentData)//
                .saleData(saleData).build();

        // Message Header
        MessageHeader messageHeader = new MessageHeader.Builder()//
                .messageClass(MessageClass.Service)//
                .messageCategory(MessageCategory.Payment)//
                .messageType(MessageType.Request)//
                .serviceID(serviceID)//
                .saleID(saleID)//
                .POIID(poiID)//
                .build();

        SecurityTrailer securityTrailer = generateSecurityTrailer(messageHeader, paymentRequest);

        SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
                .messageHeader(messageHeader)//
                .request(paymentRequest)//
                .securityTrailer(securityTrailer)//
                .build();

        return saleToPOI;
    }

    private SaleToPOIRequest buildTransactionStatusRequest(String serviceID) throws ConfigurationException {
        currentServiceID = MessageHeaderUtil.generateServiceID();
        referenceServiceID = serviceID;

        // Transaction Status Request
        MessageReference messageReference = new MessageReference.Builder()//
                .messageCategory(MessageCategory.Payment)//
                .POIID(poiID)//
                .saleID(saleID)//
                .serviceID(referenceServiceID)//
                .build();

        TransactionStatusRequest transactionStatusRequest = new TransactionStatusRequest(messageReference);

        // Message Header
        MessageHeader messageHeader = new MessageHeader.Builder()//
                .messageClass(MessageClass.Service)//
                .messageCategory(MessageCategory.TransactionStatus)//
                .messageType(MessageType.Request)//
                .serviceID(currentServiceID)//
                .saleID(saleID)//
                .POIID(poiID)//
                .build();

        SecurityTrailer securityTrailer = generateSecurityTrailer(messageHeader, transactionStatusRequest);

        SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
                .messageHeader(messageHeader)//
                .request(transactionStatusRequest)//
                .securityTrailer(securityTrailer)//
                .build();

        return saleToPOI;
    }

    private SaleToPOIRequest buildAbortRequest(String paymentServiceID, String abortReason) {
        currentServiceID = MessageHeaderUtil.generateServiceID();
        referenceServiceID = paymentServiceID;
        // Message Header
        MessageHeader messageHeader = new MessageHeader.Builder()//
                .messageClass(MessageClass.Service)//
                .messageCategory(MessageCategory.Abort)//
                .messageType(MessageType.Request)//
                .serviceID(currentServiceID)//
                .saleID(saleID)//
                .POIID(poiID)//
                .build();

        MessageReference messageReference = new MessageReference.Builder().messageCategory(MessageCategory.Abort)
                .serviceID(referenceServiceID).build();

        AbortTransactionRequest abortTransactionRequest = new AbortTransactionRequest(messageReference, abortReason);

        SecurityTrailer securityTrailer = generateSecurityTrailer(messageHeader, abortTransactionRequest);

        SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
                .messageHeader(messageHeader)//
                .request(abortTransactionRequest)//
                .securityTrailer(securityTrailer)//
                .build();

        return saleToPOI;
    }

    private SecurityTrailer generateSecurityTrailer(MessageHeader messageHeader, Request request){
            return SecurityTrailerUtil.generateSecurityTrailer(messageHeader, request, useTestEnvironment);
    }


    private Boolean handleLoginResponseMessage(SaleToPOIResponse response) {
        // TODO Clean Validations here.
        Boolean successfulLogin = false;
        response.getLoginResponse().getResponse();
        Response responseBody = response.getLoginResponse().getResponse();
        if (responseBody.getResult() != null) {
            log(String.format(" Login Result: %s ", responseBody.getResult()));
            successfulLogin = true;
            if (responseBody.getResult() != ResponseResult.Success) {
                log(String.format("Error Condition: %s, Additional Response: %s",
                        responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
                successfulLogin = false;
            }
        }
        return successfulLogin;
    }

    private Boolean handlePaymentResponseMessage(SaleToPOIResponse msg) {
        Boolean successfulPayment = false;
        Response responseBody = msg.getPaymentResponse().getResponse();
        if (responseBody.getResult() != null) {
            log(String.format("Payment Result: %s", responseBody.getResult()));
            if (responseBody.getResult() != ResponseResult.Success) {
                log(String.format("Error Condition: %s, Additional Response: %s",
                        responseBody.getErrorCondition(),
                        responseBody.getAdditionalResponse()));
                successfulPayment = false;
            }
            else{
                successfulPayment = true;
            }
        }
        return successfulPayment;
    }

    private void handleTransactionResponseMessage(SaleToPOIResponse msg) {
        Response responseBody = null;

        if (msg.getTransactionStatusResponse() != null
                && msg.getTransactionStatusResponse().getResponse() != null) {
            responseBody = msg.getTransactionStatusResponse().getResponse();
            if (responseBody.getResult() != null) {
                log(
                        String.format("Transaction Status Result: %s ", responseBody.getResult()));

                if (responseBody.getResult() == ResponseResult.Success) {
                    Response paymentResponseBody = null;

                    if (msg.getTransactionStatusResponse().getRepeatedMessageResponse() != null
                            && msg.getTransactionStatusResponse().getRepeatedMessageResponse()
                            .getRepeatedResponseMessageBody() != null
                            && msg.getTransactionStatusResponse().getRepeatedMessageResponse()
                            .getRepeatedResponseMessageBody().getPaymentResponse() != null) {

                        paymentResponseBody = msg.getTransactionStatusResponse()
                                .getRepeatedMessageResponse().getRepeatedResponseMessageBody()
                                .getPaymentResponse().getResponse();

                    }

                    if (paymentResponseBody != null) {
                        log(String.format("Actual Payment Result: %s",
                                        paymentResponseBody.getResult()));

                        if (paymentResponseBody.getErrorCondition() != null
                                || paymentResponseBody.getAdditionalResponse() != null) {
                            log(
                                    String.format("Error Condition: %s, Additional Response: %s",
                                            paymentResponseBody.getErrorCondition(),
                                            paymentResponseBody.getAdditionalResponse()));
                        }
                    }

                } else if (responseBody.getErrorCondition() == ErrorCondition.InProgress) {
                    log("Transaction in progress...");
                    log(String.format("Error Condition: %s, Additional Response: %s",
                            responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));

                    errorHandlingTimeout = (secondsRemaining - 10) * 1000; //decrement errorHandlingTimeout
                    log("Sending another transaction status request after 10 seconds...");
                    log("Remaining seconds until error handling timeout: " + secondsRemaining);
                    String serviceID = msg.getTransactionStatusResponse().getMessageReference().getServiceID();
                    try {
                        TimeUnit.SECONDS.sleep(10);
                        checkTransactionStatus(serviceID, "");
                    } catch (InterruptedException e) {
                        log(e);
                    }

                } else {
                    log(String.format("Error Condition: %s, Additional Response: %s",
                            responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
                }
            }
        }
    }

    private void log(Exception ex){
        log(ex.getMessage());
        waitingForResponse = false;
    }
    private void log(String logData, Boolean stopWaiting) {
        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + ": " + logData); // 2021.03.24.16.34.26
        if(stopWaiting){
            waitingForResponse = false;
        }
    }

    private void log(String logData) {
        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + ": " + logData); // 2021.03.24.16.34.26
    }

    public long printSecondsRemaining(String transaction, long start) {
        long currentTime = System.currentTimeMillis();
        long sec = (currentTime - start) / 1000;
        if(sec==1) {
            log("("+ transaction +") seconds remaining: " + (secondsRemaining--));
            start = currentTime;
        }
        return start;
    }
}
