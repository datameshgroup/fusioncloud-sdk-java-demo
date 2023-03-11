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
import au.com.dmg.fusion.request.displayrequest.DisplayRequest;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;

public class FusionTransactionProcessor {
    private FusionClient fusionClient;

    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    String providerIdentification = "Company A"; // test environment only - replace for production
    String applicationName = "POS Retail"; // test environment only - replace for production
    String softwareVersion = "01.00.00"; // test environment only - replace for production
    String certificationCode = "98cf9dfc-0db7-4a92-8b8cb66d4d2d7169"; // test environment only - replace for production
    private String saleID;
    private String poiID;
    private String kek;
    boolean useTestEnvironment = true;

    //Timer settings; Update as needed.
    int loginTimeout = 60;
    int paymentTimeout = 60; //60
    int errorHandlingTimeout = 90; //90

    boolean waitingForResponse;
    int secondsRemaining;
    long prevTime;

    MessageCategory currentTransaction = MessageCategory.Login;
    String currentServiceID;
    String referenceServiceID;

    ExecutorService executor = Executors.newSingleThreadExecutor();
    Future<Boolean> responseMessage;
    Callable<Boolean> callableMessageListener;

    public FusionTransactionProcessor() {
        //these config values need to be configurable in POS
        saleID = "VA POS"; // Replace with your test SaleId provided by DataMesh
        poiID = "DMGVA002"; // Replace with your test POIID provided by DataMesh

        fusionClient = new FusionClient(useTestEnvironment); //need to override this in production
        kek = "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21"; //for dev only, need to be replaced with prod value in prod
        fusionClient.setSettings(saleID, poiID, kek); // replace with the Sale ID provided by DataMesh
    }

    public void initiatePaymentTransaction() {
        try {
            fusionClient.connect();

            // Response Listener
            callableMessageListener = () -> {
                {
                    log("Enters future----------");
                    boolean gotValidResponse = false;
                    Map<String, Boolean> responseResult = new HashMap<String, Boolean>();
                    //Prepare for timer print
                    prevTime = System.nanoTime();
                    secondsRemaining = paymentTimeout;

                    try {
                        // Wait for response & handle
                        waitingForResponse = true; // TODO: timeout handling
                        while(waitingForResponse) {
                            prevTime = printSecondsRemaining(currentTransaction.toString(), prevTime);

                            SaleToPOI saleToPOI = fusionClient.readMessage();

                            if(saleToPOI == null) {
                                continue;
                            }

                            // Handles Display Request
                            if( saleToPOI instanceof SaleToPOIRequest ) {
                                waitingForResponse = false;
                                handleRequestMessage(saleToPOI);
                                continue;
                            }

                            if( saleToPOI instanceof SaleToPOIResponse ) {
                                SaleToPOIResponse response = (SaleToPOIResponse) saleToPOI;
                                log(String.format("Response(JSON): %s", response.toJson()));
                                MessageCategory messageCategory = response.getMessageHeader().getMessageCategory();
                                log("Message Category: " + messageCategory);

                                if(!messageCategory.equals(currentTransaction) && !messageCategory.equals(MessageCategory.Event)){
                                    log("Ignoring unexpected response above... /n" +
                                            "Expected message category is: "+ currentTransaction +
                                            "/n, Received: " + messageCategory);
                                    continue;
                                }

                                switch (messageCategory){
                                    case Event:
                                        EventNotification eventNotification = response.getEventNotification();
                                        log("Event Details: " + eventNotification.getEventDetails());
                                        break;
                                    case Login:
                                        responseResult = handleLoginResponseMessage(response);
                                        waitingForResponse = responseResult.getOrDefault("WaitingForAnotherResponse", false);
                                        break;
                                    case Payment:
                                        responseResult = handlePaymentResponseMessage(saleToPOI);
                                        waitingForResponse = responseResult.getOrDefault("WaitingForAnotherResponse", true);
                                        break;
                                    case TransactionStatus:
                                        responseResult = handleTransactionResponseMessage(saleToPOI);
                                        waitingForResponse = responseResult.getOrDefault("WaitingForAnotherResponse", true);
                                        break;
                                }
                                if (!waitingForResponse) {
                                    gotValidResponse = responseResult.getOrDefault("GotValidResponse", false);
                                }
                            } else{
                                //TODO:verify this
                                log("Unexpected response message received.");
                            }
                        }
                    } catch (FusionException e) {
                        log(e);
                    }

                    return gotValidResponse;
                }
            };

            if(doLogin()) {
                doPayment();
            }

            fusionClient.disconnect();
            log("Disconnected from websocket server");
            executor.shutdown();
        } catch (FusionException e) {
            log(e);
        } catch (IOException e) {
            log(e);
        }
    }

    private boolean doLogin() {
        currentServiceID = MessageHeaderUtil.generateServiceID();
        try {
            SaleToPOIRequest loginRequest = buildLoginRequest(currentServiceID);
            log("Sending message to websocket server: " + "\n" + loginRequest);
            fusionClient.sendMessage(loginRequest);
            currentTransaction = MessageCategory.Login;
        } catch (ConfigurationException e) {
            log(e);
        }

        boolean gotValidResponse = false;
        try {
            responseMessage = executor.submit(callableMessageListener);
            gotValidResponse = responseMessage.get(loginTimeout, TimeUnit.SECONDS); // set timeout
        } catch (TimeoutException e) {
            System.err.println("Payment Request Timeout...");
        } catch (ExecutionException | InterruptedException e) {
            log(String.format("Exception: %s", e.toString()), true);
        } finally {
           log("isDone " + responseMessage.isDone());
        }
        return gotValidResponse;
    }

    private void doPayment() {
        currentServiceID = MessageHeaderUtil.generateServiceID();

        //Preparing for Transaction Status Check
        referenceServiceID = currentServiceID;
        try {
            SaleToPOIRequest paymentRequest = buildPaymentRequest(currentServiceID);
            log("Sending message to websocket server: " + "\n" + paymentRequest);
            fusionClient.sendMessage(paymentRequest);
            currentTransaction = MessageCategory.Payment;
        } catch (ConfigurationException e) {
            log(e);
        }

        boolean gotValidResponse = false;
        String abortReason = "";
        try {
            responseMessage = executor.submit(callableMessageListener);
            gotValidResponse = responseMessage.get(paymentTimeout, TimeUnit.SECONDS); // set timeout
        } catch (TimeoutException e) {
            System.err.println("Payment Request Timeout...");
            abortReason = "Timeout";
        } catch (ExecutionException | InterruptedException e) {
            log(String.format("Exception: %s", e.toString()), true);
            abortReason = "Other Exception";
        } finally {
//            executor.shutdownNow();
            log("isDone " + responseMessage.isDone());
//            if (!gotValidResponse)
//                checkTransactionStatus(referenceServiceID, abortReason);
        }
    }

    private void checkTransactionStatus(String serviceID, String abortReason) {

        try {
            SaleToPOIRequest transactionStatusRequest = buildTransactionStatusRequest(serviceID);
        } catch (ConfigurationException e) {
            throw new RuntimeException(e);
        }

        log("Sending transaction status request to check status of payment...");

        try {
            responseMessage = executor.submit(callableMessageListener);
            responseMessage.get(errorHandlingTimeout, TimeUnit.SECONDS); // set timeout
        } catch (TimeoutException e) {
            System.err.println("Transaction Status Timeout...");
        } catch (ExecutionException | InterruptedException e) {
            log(String.format("Exception: %s", e.toString()), true);
        } finally {
//            executor.shutdownNow();
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
                .productCode("")
//                .productCode("DMGTC44855")// Update this for Mock host testing
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

    private SaleToPOIRequest buildAbortRequest(String paymentServiceID, String abortReason)
            throws ConfigurationException {

        // Message Header
        MessageHeader messageHeader = new MessageHeader.Builder()//
                .messageClass(MessageClass.Service)//
                .messageCategory(MessageCategory.Abort)//
                .messageType(MessageType.Request)//
                .serviceID(MessageHeaderUtil.generateServiceID())//
                .saleID(saleID)//
                .POIID(poiID)//
                .build();

        MessageReference messageReference = new MessageReference.Builder().messageCategory(MessageCategory.Abort)
                .serviceID(paymentServiceID).build();

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

    // Refresh timer every time there's a response from host
    private void refreshTimer(MessageCategory mc){

        log("start isDone " + responseMessage.isDone());
        waitingForResponse = false;
        log("end isDone " + responseMessage.isDone());

        switch (currentTransaction){
            case Payment:
                secondsRemaining = paymentTimeout;
                break;
            case Login:
                secondsRemaining = loginTimeout;
                break;
            case TransactionStatus:
                secondsRemaining = errorHandlingTimeout;
                break;
        }
        log("Refreshing timer to " + secondsRemaining);

        try {
            responseMessage = executor.submit(callableMessageListener);
            responseMessage.get(secondsRemaining, TimeUnit.SECONDS); // set timeout
        } catch (TimeoutException e) {
            System.err.println(mc + " Request Timeout...");
        } catch (ExecutionException | InterruptedException e) {
            log(String.format("Exception: %s", e.toString()), true);
        } finally {
            log("isDone " + responseMessage.isDone());
        }

    }

    private void handleRequestMessage(SaleToPOI msg) {
        MessageCategory messageCategory = MessageCategory.Other;
        if (msg instanceof SaleToPOIRequest) {
            SaleToPOIRequest request = (SaleToPOIRequest) msg;
            log(String.format("Request(JSON): %s", request.toJson()));
            if (request.getMessageHeader() != null)
                messageCategory = request.getMessageHeader().getMessageCategory();
            if (messageCategory == MessageCategory.Display) {
                DisplayRequest displayRequest = request.getDisplayRequest();
                if (displayRequest != null) {
                    log("Display Output = " + displayRequest.getDisplayText());
                    //TODO: Update timer properly
                    refreshTimer(messageCategory);

                }
            } else
                log(messageCategory + " received during response message handling.");
        } else {
            log("Unexpected request message received.");
        }
    }

    private Map<String, Boolean> handleLoginResponseMessage(SaleToPOIResponse response) {
        Map<String, Boolean> responseResult = new HashMap<String, Boolean>();
        if(response.getLoginResponse() != null) {
            response.getLoginResponse().getResponse();
            Response responseBody = response.getLoginResponse().getResponse();
            if (responseBody.getResult() != null) {
                log(String.format("Login Result: %s ", responseBody.getResult()));
                if (responseBody.getResult() != ResponseResult.Success) {
                    log(String.format("Error Condition: %s, Additional Response: %s",
                            responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
                }
                responseResult.put("GotValidResponse",true);
                responseResult.put("WaitingForAnotherResponse",false);
            }
        }
        return responseResult;
    }

    private  Map<String, Boolean> handlePaymentResponseMessage(SaleToPOI msg) {
        Map<String, Boolean> responseResult = new HashMap<String, Boolean>();
        MessageCategory messageCategory;
        if (msg instanceof SaleToPOIResponse) {
            SaleToPOIResponse response = (SaleToPOIResponse) msg;
            log(String.format("Response(JSON): %s", response.toJson()));
            response.getMessageHeader();
            messageCategory = response.getMessageHeader().getMessageCategory();
            Response responseBody = null;
            log("Message Category: " + messageCategory);
            switch (messageCategory) {
                case Event:
                    EventNotification eventNotification = response.getEventNotification();
                    log("Event Details: " + eventNotification.getEventDetails());
                    break;
                case Payment:
                    responseBody = response.getPaymentResponse().getResponse();
                    if (responseBody.getResult() != null) {
                        log(String.format("Payment Result: %s", responseBody.getResult()));
                        if (responseBody.getResult() != ResponseResult.Success) {
                            log(String.format("Error Condition: %s, Additional Response: %s",
                                    responseBody.getErrorCondition(),
                                    responseBody.getAdditionalResponse()));
                        }
                        responseResult.put("GotValidResponse", true);
                    }
                    responseResult.put("WaitingForAnotherResponse", false);
                    break;
                default:
                    log(messageCategory + " received during Payment response message handling.");
                    break;
            }
        } else
            log("Unexpected response message received.");

        return responseResult;
    }

    private  Map<String, Boolean> handleTransactionResponseMessage(SaleToPOI msg) {
        Map<String, Boolean> responseResult = new HashMap<String, Boolean>();
        MessageCategory messageCategory = MessageCategory.Other;
        if (msg instanceof SaleToPOIResponse) {
            SaleToPOIResponse response = (SaleToPOIResponse) msg;
            log(String.format("Response(JSON): %s", response.toJson()));
            response.getMessageHeader();
            messageCategory = response.getMessageHeader().getMessageCategory();
            Response responseBody = null;
            log("Message Category: " + messageCategory);
            switch (messageCategory) {
                case Event:
                    EventNotification eventNotification = response.getEventNotification();
                    log("Event Details: " + eventNotification.getEventDetails());
                    break;
                case TransactionStatus:
                    if (response.getTransactionStatusResponse() != null
                            && response.getTransactionStatusResponse().getResponse() != null) {
                        responseBody = response.getTransactionStatusResponse().getResponse();
                        if (responseBody.getResult() != null) {
                            log(
                                    String.format("Transaction Status Result: %s ", responseBody.getResult()));

                            if (responseBody.getResult() == ResponseResult.Success) {
                                Response paymentResponseBody = null;

                                if (response.getTransactionStatusResponse().getRepeatedMessageResponse() != null
                                        && response.getTransactionStatusResponse().getRepeatedMessageResponse()
                                        .getRepeatedResponseMessageBody() != null
                                        && response.getTransactionStatusResponse().getRepeatedMessageResponse()
                                        .getRepeatedResponseMessageBody().getPaymentResponse() != null) {

                                    paymentResponseBody = response.getTransactionStatusResponse()
                                            .getRepeatedMessageResponse().getRepeatedResponseMessageBody()
                                            .getPaymentResponse().getResponse();

                                }

                                if (paymentResponseBody != null) {
                                    log(
                                            String.format("Actual Payment Result: %s",
                                                    paymentResponseBody.getResult()));

                                    if (paymentResponseBody.getErrorCondition() != null
                                            || paymentResponseBody.getAdditionalResponse() != null) {
                                        log(
                                                String.format("Error Condition: %s, Additional Response: %s",
                                                        paymentResponseBody.getErrorCondition(),
                                                        paymentResponseBody.getAdditionalResponse()));
                                    }
                                }
                                responseResult.put("GotValidResponse",true);
                                responseResult.put("WaitingForAnotherResponse",false);

                            } else if (responseBody.getErrorCondition() == ErrorCondition.InProgress) {
                                log("Payment in progress...");
                                log(String.format("Error Condition: %s, Additional Response: %s",
                                        responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
                                responseResult.put("BuildAndSendRequestMessage",true);
                            } else {
                                log(String.format("Error Condition: %s, Additional Response: %s",
                                        responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
                                responseResult.put("GotValidResponse",true);
                                responseResult.put("WaitingForAnotherResponse",false);
                            }
                        }
                    }
                default:
                    //TODO check where to put this
//                    log(messageCategory + " received during Transaction Status response message handling.");
                    log("Unrecognised message category", true);
                    break;
            }
        } else
            log("Unexpected response message received.");

        return responseResult;
    }

    private void log(Exception ex){
        log(ex.getMessage());
        waitingForResponse = false;
    }
    private void log(String logData, Boolean err) {
        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + " " + logData); // 2021.03.24.16.34.26
        if(err){
            waitingForResponse = false;
            executor.shutdownNow();
        }
    }

    private void log(String logData) {
        System.out.println(sdf.format(new Date(System.currentTimeMillis())) + " " + logData); // 2021.03.24.16.34.26
    }

    public long printSecondsRemaining(String transaction, long start) {
        long currentTime = System.nanoTime();
        float sec = (currentTime - start) / 1000000000;
        if(sec==1) {
            log("("+ transaction +") seconds remaining: " + (secondsRemaining--));
            start = currentTime;
        }
        return start;
    }
}
