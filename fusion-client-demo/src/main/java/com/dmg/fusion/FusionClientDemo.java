package com.dmg.fusion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.websocket.DeploymentException;

import com.dmg.fusion.client.FusionClient;
import com.dmg.fusion.config.FusionClientConfig;
import com.dmg.fusion.config.SaleSystemConfig;
import com.dmg.fusion.util.MessageHeaderUtil;
import com.dmg.fusion.util.SecurityTrailerUtil;

import au.com.dmg.fusion.MessageHeader;
import au.com.dmg.fusion.data.MessageCategory;
import au.com.dmg.fusion.data.MessageClass;
import au.com.dmg.fusion.data.MessageType;
import au.com.dmg.fusion.data.PaymentInstrumentType;
import au.com.dmg.fusion.data.PaymentType;
import au.com.dmg.fusion.data.SaleCapability;
import au.com.dmg.fusion.data.TerminalEnvironment;
import au.com.dmg.fusion.data.UnitOfMeasure;
import au.com.dmg.fusion.request.SaleTerminalData;
import au.com.dmg.fusion.request.SaleToPOIRequest;
import au.com.dmg.fusion.request.loginrequest.LoginRequest;
import au.com.dmg.fusion.request.loginrequest.SaleSoftware;
import au.com.dmg.fusion.request.logoutrequest.LogoutRequest;
import au.com.dmg.fusion.request.paymentrequest.AmountsReq;
import au.com.dmg.fusion.request.paymentrequest.PaymentData;
import au.com.dmg.fusion.request.paymentrequest.PaymentInstrumentData;
import au.com.dmg.fusion.request.paymentrequest.PaymentRequest;
import au.com.dmg.fusion.request.paymentrequest.PaymentTransaction;
import au.com.dmg.fusion.request.paymentrequest.SaleData;
import au.com.dmg.fusion.request.paymentrequest.SaleItem;
import au.com.dmg.fusion.request.paymentrequest.SaleTransactionID;
import au.com.dmg.fusion.request.reconciliationrequest.ReconciliationRequest;
import au.com.dmg.fusion.request.transactionstatusrequest.MessageReference;
import au.com.dmg.fusion.request.transactionstatusrequest.TransactionStatusRequest;
import au.com.dmg.fusion.response.SaleToPOIResponse;
import au.com.dmg.fusion.securitytrailer.SecurityTrailer;

public class FusionClientDemo {

	public static final String SALE_ID = "BlackLabelUAT1";
	public static final String POI_ID = "BLBPOI02";
	public static final String KEK = "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21";

	public static void main(String[] args) throws IOException, CertificateException, KeyStoreException {
		FusionClient fusionClient = new FusionClient();

		try {
			fusionClient.connect(new URI(FusionClientConfig.getInstance().getServerDomain()));
		} catch (KeyManagementException | NoSuchAlgorithmException | DeploymentException | IOException
				| URISyntaxException e) {
			e.printStackTrace();
		}

		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

		outerloop: while (true) {
			System.out.print("\nEnter request (login, logout, payment, transaction status, reconciliation): ");

			String operation = null;
			try {
				operation = br.readLine();
			} catch (IOException e) {
				System.out.println(e);
			}

			SaleToPOIRequest message = null;

			switch (operation.toLowerCase()) {
			case "login":
				message = buildLoginRequest();
				break;
			case "logout":
				message = buildLogoutRequest();
				break;
			case "payment":
				message = buildPaymentRequest();
				break;
			case "reconciliation":
				message = buildReconciliationRequest();
				break;
			case "transaction status":
				message = buildTransactionStatusRequest();
				break;
			default:
				System.out.println("Invalid request");
			}

			String userResponse = "";

			if (message != null) {
				System.out.println("Sending message to websocket server: " + "\n" + message);
				fusionClient.sendMessage(message);
				String serviceID = message.getMessageHeader().getServiceID();
				message = null;

				if (operation.equals("logout")) {
					try {
						Thread.sleep(5000);
						System.out.println(fusionClient.inQueueResponse.take());
						fusionClient.disconnect();
						break outerloop;
					} catch (IOException | InterruptedException e) {
						e.printStackTrace();
					}
				}

				// check for response from server
				try {
					innerloop: while (true) {
						Thread.sleep(3000); // wait for 3 seconds

						Optional<SaleToPOIResponse> optResponse = fusionClient.inQueueResponse.stream()
								.filter(r -> r.getMessageHeader().getServiceID().equals(serviceID)).findFirst();

						if (optResponse.isPresent()) {
							System.out.println("Received response from websocket server for request with service ID " + serviceID + ": ");
							System.out.println(optResponse.get().toJson());
							break innerloop;
						} else {
							System.out.println(
									"Did not receive a request/response from the server for request with service ID: "
											+ serviceID);
							
							do {
								System.out.print("\nWait for another 3 seconds (yes/no)? ");
								userResponse = br.readLine();
							} while (!userResponse.equalsIgnoreCase("no") && !userResponse.equalsIgnoreCase("yes"));

							if (userResponse.equalsIgnoreCase("no")) {
								break innerloop;
							}
						}
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}

			do {
				System.out.print("\nSend another request (yes/no)? ");
				userResponse = br.readLine();
			} while (!userResponse.equalsIgnoreCase("no") && !userResponse.equalsIgnoreCase("yes"));

			if (userResponse.equalsIgnoreCase("no")) {
				fusionClient.disconnect();
				break outerloop;
			}
		}
	}

	private static SaleToPOIRequest buildLoginRequest() throws IOException {
		// Login Request
		SaleSoftware saleSoftware = new SaleSoftware.Builder()//
				.providerIdentification(SaleSystemConfig.getInstance().getProviderIdentification())//
				.applicationName(SaleSystemConfig.getInstance().getApplicationName())//
				.softwareVersion(SaleSystemConfig.getInstance().getSoftwareVersion())//
				.certificationCode(SaleSystemConfig.getInstance().getCertificationCode())//
				.build();

		SaleTerminalData saleTerminalData = new SaleTerminalData.Builder()//
				.terminalEnvironment(TerminalEnvironment.SemiAttended)//
				.saleCapabilities(Arrays.asList(SaleCapability.CashierStatus, SaleCapability.CustomerAssistance))//
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
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, loginRequest, KEK);
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException
				| UnsupportedEncodingException | InvalidKeySpecException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(loginRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}

	private static SaleToPOIRequest buildLogoutRequest() {
		// Logout Request
		LogoutRequest logoutRequest = new LogoutRequest(false);

		// Message Header
		MessageHeader messageHeader = new MessageHeader.Builder()//
				.messageClass(MessageClass.Service)//
				.messageCategory(MessageCategory.Logout)//
				.messageType(MessageType.Request)//
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, logoutRequest, KEK);
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException | InvalidKeySpecException
				| IOException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(logoutRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}

	private static SaleToPOIRequest buildPaymentRequest() {
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
				.productCode("productCode")//
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
				.paymentInstrumentData(paymentInstrumentData).build();

		PaymentTransaction paymentTransaction = new PaymentTransaction.Builder()//
				.amountsReq(amountsReq)//
				.addSaleItem(saleItem)//
				.paymentData(paymentData)//
				.build();

		PaymentRequest paymentRequest = new PaymentRequest.Builder()//
				.paymentTransaction(paymentTransaction)//
				.saleData(saleData).build();

		// Message Header
		MessageHeader messageHeader = new MessageHeader.Builder()//
				.messageClass(MessageClass.Service)//
				.messageCategory(MessageCategory.Payment)//
				.messageType(MessageType.Request)//
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, paymentRequest, KEK);
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException | InvalidKeySpecException
				| IOException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(paymentRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}

	private static SaleToPOIRequest buildReconciliationRequest() {
		// Reconciliation Request
		ReconciliationRequest reconciliationRequest = new ReconciliationRequest("SaleReconciliation", null);

		// Message Header
		MessageHeader messageHeader = new MessageHeader.Builder()//
				.messageClass(MessageClass.Service)//
				.messageCategory(MessageCategory.Reconciliation)//
				.messageType(MessageType.Request)//
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, reconciliationRequest, KEK);
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException | InvalidKeySpecException
				| IOException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(reconciliationRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}

	private static SaleToPOIRequest buildTransactionStatusRequest() {
		// Transaction Status Request
		MessageReference messageReference = new MessageReference.Builder()//
				.messageCategory(MessageCategory.Payment)//
				.build();

		TransactionStatusRequest transactionStatusRequest = new TransactionStatusRequest(messageReference);

		// Message Header
		MessageHeader messageHeader = new MessageHeader.Builder()//
				.messageClass(MessageClass.Service)//
				.messageCategory(MessageCategory.TransactionStatus)//
				.messageType(MessageType.Request)//
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, transactionStatusRequest, KEK);
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException | InvalidKeySpecException
				| IOException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(transactionStatusRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}
}
