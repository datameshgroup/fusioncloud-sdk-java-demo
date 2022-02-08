package com.dmg.fusion;

import java.io.IOException;
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
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.naming.ConfigurationException;
import javax.websocket.DeploymentException;

import org.w3c.dom.events.Event;

import au.com.dmg.fusion.MessageHeader;
import au.com.dmg.fusion.client.FusionClient;
import au.com.dmg.fusion.config.FusionClientConfig;
import au.com.dmg.fusion.config.KEKConfig;
import au.com.dmg.fusion.config.SaleSystemConfig;
import au.com.dmg.fusion.data.ErrorCondition;
import au.com.dmg.fusion.data.MessageCategory;
import au.com.dmg.fusion.data.MessageClass;
import au.com.dmg.fusion.data.MessageType;
import au.com.dmg.fusion.data.PaymentInstrumentType;
import au.com.dmg.fusion.data.PaymentType;
import au.com.dmg.fusion.data.SaleCapability;
import au.com.dmg.fusion.data.TerminalEnvironment;
import au.com.dmg.fusion.data.UnitOfMeasure;
import au.com.dmg.fusion.exception.NotConnectedException;
import au.com.dmg.fusion.request.SaleTerminalData;
import au.com.dmg.fusion.request.SaleToPOIRequest;
import au.com.dmg.fusion.request.aborttransactionrequest.AbortTransactionRequest;
import au.com.dmg.fusion.request.displayrequest.DisplayRequest;
import au.com.dmg.fusion.request.loginrequest.LoginRequest;
import au.com.dmg.fusion.request.loginrequest.SaleSoftware;
import au.com.dmg.fusion.request.paymentrequest.AmountsReq;
import au.com.dmg.fusion.request.paymentrequest.PaymentData;
import au.com.dmg.fusion.request.paymentrequest.PaymentInstrumentData;
import au.com.dmg.fusion.request.paymentrequest.PaymentRequest;
import au.com.dmg.fusion.request.paymentrequest.PaymentTransaction;
import au.com.dmg.fusion.request.paymentrequest.SaleData;
import au.com.dmg.fusion.request.paymentrequest.SaleItem;
import au.com.dmg.fusion.request.paymentrequest.SaleTransactionID;
import au.com.dmg.fusion.request.transactionstatusrequest.MessageReference;
import au.com.dmg.fusion.request.transactionstatusrequest.TransactionStatusRequest;
import au.com.dmg.fusion.response.Response;
import au.com.dmg.fusion.response.ResponseResult;
import au.com.dmg.fusion.response.SaleToPOIResponse;
import au.com.dmg.fusion.response.paymentresponse.PaymentResponse;
import au.com.dmg.fusion.securitytrailer.SecurityTrailer;
import au.com.dmg.fusion.util.MessageHeaderUtil;
import au.com.dmg.fusion.util.SecurityTrailerUtil;
import au.com.dmg.fusion.response.DisplayResponse;
import au.com.dmg.fusion.response.EventNotification;
import au.com.dmg.fusion.response.OutputResult;
import au.com.dmg.fusion.SaleToPOI;
import au.com.dmg.fusion.request.displayrequest.DisplayRequest;

public class FusionClientDemo {

	public static final String SALE_ID;
	public static final String POI_ID;
	private static final FusionClient fusionClient = new FusionClient();
	private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

	static {
		initConfig();
		SALE_ID = "SALE ID"; // test environment only - replace for production
		POI_ID = "POI ID"; // test environment only - replace for production
	}

	public static void main(String[] args) {
		try {
			fusionClient.connect(new URI(FusionClientConfig.getInstance().getServerDomain()));

			doLogin();
			doPayment();

			fusionClient.disconnect();
			log("Disconnected from websocket server.");
		} catch (ConfigurationException e) {
			log(String.format("ConfigurationException: %s", e.toString()));
		} catch (KeyManagementException | NoSuchAlgorithmException | DeploymentException | IOException
				| URISyntaxException | CertificateException | KeyStoreException e) {
			log(String.format("Exception: %s", e.toString()));
		}
	}

	private static void initConfig() {
		log("Current Working Directory = " + System.getProperty("user.dir"));
		String certificateLocation = "src/main/resources/root.crt"; // test environment only - replace for production
		String serverDomain = "wss://www.cloudposintegration.io/nexodev"; // test environment only - replace for
																			// production
		String socketProtocol = "TLSv1.2";

		String kekValue = "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21"; // test environment only - replace for
																				// production
		String keyIdentifier = "SpecV2TestMACKey"; // test environment only - replace for production
		String keyVersion = "20191122164326.594"; // test environment only - replace for production

		String providerIdentification = "Company A"; // test environment only - replace for production
		String applicationName = "POS Retail"; // test environment only - replace for production
		String softwareVersion = "01.00.00"; // test environment only - replace for production
		String certificationCode = "98cf9dfc-0db7-4a92-8b8cb66d4d2d7169"; // test environment only - replace for
																			// production

		try {
			FusionClientConfig.init(certificateLocation, serverDomain, socketProtocol);
			KEKConfig.init(kekValue, keyIdentifier, keyVersion);
			SaleSystemConfig.init(providerIdentification, applicationName, softwareVersion, certificationCode);
		} catch (ConfigurationException e) {
			log(String.format("ConfigurationException: %s", e.toString())); // Ensure all config fields have values
		}
	}

	private static void doLogin() {
		SaleToPOIRequest loginRequest = null;
		try {
			loginRequest = buildLoginRequest();

			log("Sending message to websocket server: " + "\n" + loginRequest);
			fusionClient.sendMessage(loginRequest);

			boolean waitingForResponse = true;

			while (waitingForResponse) {
				Optional<SaleToPOIResponse> optResponse = null;
				try {
					optResponse = Optional.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					log(String.format("InterruptedException: %s", e.toString()));
				}

				if (optResponse.isPresent()) {
					SaleToPOIResponse response = optResponse.get();
					log(String.format("Response(JSON): %s", response.toJson()));

					boolean serviceIDMatchesRequest = response.getMessageHeader() != null //
							&& response.getMessageHeader().getServiceID()
									.equalsIgnoreCase(loginRequest.getMessageHeader().getServiceID());

					if (serviceIDMatchesRequest && response.getLoginResponse() != null //
							&& response.getLoginResponse().getResponse() != null) {
						Response responseBody = response.getLoginResponse().getResponse();

						if (responseBody.getResult() != null) {
							log(String.format("Login Result: %s ", responseBody.getResult()));

							if (responseBody.getResult() != ResponseResult.Success) {
								log(String.format("Error Condition: %s, Additional Response: %s",
										responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
							}
						}
						waitingForResponse = false;
					}
				}
			}
		} catch (ConfigurationException e) {
			log(String.format("ConfigurationException: %s", e.toString()));
		} catch (NotConnectedException e) {
			log(String.format("NotConnectedException: %s", e.toString()));
		}
	}

	private static void doPayment() {
		String serviceID = MessageHeaderUtil.generateServiceID(10);

		// Check for display requests
		Thread displayRequestThread = new Thread(new Runnable() {
			public void run() {
				while (!Thread.currentThread().isInterrupted()) {
					Optional<SaleToPOIRequest> optMessageRequest;
					try {
						optMessageRequest = Optional.ofNullable(fusionClient.inQueueRequest.poll(1, TimeUnit.SECONDS));
						optMessageRequest.ifPresent(o -> handleRequestMessage(o));
					} catch (InterruptedException e) {
						log("Stop polling for display requests...");
						break;
					}
				}
			}
		});

		String abortReason = "";

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Boolean> payment = executor.submit(() -> {
			SaleToPOIRequest paymentRequest = null;
			boolean gotValidResponse = false;
			// Payment request
			try {
				paymentRequest = buildPaymentRequest(serviceID);

				log("Sending message to websocket server: " + "\n" + paymentRequest);
				fusionClient.sendMessage(paymentRequest);

				boolean waitingForResponse = true;
				displayRequestThread.start();

				while (waitingForResponse) {
					Optional<SaleToPOIResponse> optResponse = Optional
							.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));

					if (optResponse.isPresent()) {
						Map<String, Boolean> responseResult = handleResponseMessage(paymentRequest, optResponse.get());

						if (responseResult.containsKey("WaitingForAnotherResponse"))
							waitingForResponse = responseResult.get("WaitingForAnotherResponse");
						else
							waitingForResponse = true;

						if (!waitingForResponse) {
							if (responseResult.containsKey("GotValidResponse"))
								gotValidResponse = responseResult.get("GotValidResponse");
							else
								gotValidResponse = false;
						}
					}
				}
			} catch (ConfigurationException e) {
				log(String.format("ConfigurationException: %s", e.toString()));
			} catch (NotConnectedException e) {
				log(String.format("NotConnectedException: %s", e.toString()));
			} finally {
				displayRequestThread.interrupt();
			}
			return gotValidResponse;
		});

		boolean gotValidResponse = false;
		try {
			gotValidResponse = payment.get(60, TimeUnit.SECONDS); // set timeout
			if (!gotValidResponse)
				abortReason = "Timeout";
		} catch (TimeoutException e) {
			System.err.println("Payment Request Timeout...");
			abortReason = "Timeout";
		} catch (ExecutionException | InterruptedException e) {
			log(String.format("Exception: %s", e.toString()));
			abortReason = "Other Exception";
		} finally {
			executor.shutdownNow();
			if (!gotValidResponse)
				checkTransactionStatus(serviceID, abortReason);
		}
	}

	private static void handleRequestMessage(SaleToPOI msg) {
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
				}
			} else
				log(messageCategory + " received during response message handling.");
		} else
			log("Unexpected request message received.");
	}

	private static Map<String, Boolean> handleResponseMessage(SaleToPOIRequest paymentRequest, SaleToPOI msg) {
		Map<String, Boolean> responseResult = new HashMap<String, Boolean>();
		MessageCategory messageCategory = MessageCategory.Other;
		if (msg instanceof SaleToPOIResponse) {
			SaleToPOIResponse response = (SaleToPOIResponse) msg;
			log(String.format("Response(JSON): %s", response.toJson()));
			if (response.getMessageHeader() != null) {
				messageCategory = response.getMessageHeader().getMessageCategory();
				log("Message Category: " + messageCategory);
				switch (messageCategory) {
					case Event:
						EventNotification eventNotification = response.getEventNotification();
						log("Event Details: " + eventNotification.getEventDetails());
						break;
					case Payment:
						if (response.getMessageHeader().getServiceID()
								.equalsIgnoreCase(paymentRequest.getMessageHeader().getServiceID())) {
							{
								Response responseBody = response.getPaymentResponse().getResponse();
								if (responseBody.getResult() != null) {
									log(String.format("Payment Result: %s", responseBody.getResult()));

									if (responseBody.getResult() != ResponseResult.Success) {
										log(String.format("Error Condition: %s, Additional Response: %s",
												responseBody.getErrorCondition(),
												responseBody.getAdditionalResponse()));
									}
									responseResult.put("GotValidResponse", true);
								}
							}
							responseResult.put("WaitingForAnotherResponse", false);
						} else {
							log("Service ID of response message doesn't match the request.");
						}
						break;
					default:
						log(messageCategory + " received during response message handling.");
						break;
				}
			}
		} else
			log("Unexpected response message received.");

		return responseResult;
	}

	private static void checkTransactionStatus(String serviceID, String abortReason) {
		log("Sending transaction status request to check status of payment...");

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Boolean> transaction = executor.submit(() -> {
			SaleToPOIRequest transactionStatusRequest = null;
			boolean gotValidResponse = false;
			try {

				if (abortReason != "") {
					SaleToPOIRequest abortTransactionPOIRequest = buildAbortRequest(serviceID, abortReason);

					log("Sending abort message to websocket server: " + "\n" + abortTransactionPOIRequest);
					fusionClient.sendMessage(abortTransactionPOIRequest);
				}

				boolean buildAndSendRequestMessage = true;

				boolean waitingForResponse = true;

				while (waitingForResponse) {
					if (buildAndSendRequestMessage) {
						transactionStatusRequest = buildTransactionStatusRequest(serviceID);

						log("Sending message to websocket server: " + "\n" + transactionStatusRequest);
						fusionClient.sendMessage(transactionStatusRequest);
					}
					buildAndSendRequestMessage = false;
					Optional<SaleToPOIResponse> optResponse = Optional
							.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));

					if (optResponse.isPresent()) {
						SaleToPOIResponse response = optResponse.get();
						log(String.format("Response(JSON): %s", response.toJson()));
						MessageCategory messageCategory = response.getMessageHeader().getMessageCategory();
						log("Message Category: " + messageCategory);
						if (messageCategory == MessageCategory.Event) {
							EventNotification eventNotification = response.getEventNotification();
							log("Event Details: " + eventNotification.getEventDetails());
							continue;
						} else if (messageCategory == MessageCategory.Payment) {
							log("Disregard Payment response during transaction status checking.");
							continue;
						}

						boolean serviceIDMatchesRequest = response.getMessageHeader() != null
								&& response.getMessageHeader().getServiceID()
										.equalsIgnoreCase(transactionStatusRequest.getMessageHeader().getServiceID());
						if (!serviceIDMatchesRequest) {
							log(String.format("Service ID mismatch.  Expected %s but got %s.",
									transactionStatusRequest.getMessageHeader().getServiceID(),
									response.getMessageHeader().getServiceID()));
						} else if (response.getTransactionStatusResponse() != null
								&& response.getTransactionStatusResponse().getResponse() != null) {
							Response responseBody = response.getTransactionStatusResponse().getResponse();

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
									gotValidResponse = true;
									waitingForResponse = false;

								} else if (responseBody.getErrorCondition() == ErrorCondition.InProgress) {
									log("Payment in progress...");
									log(String.format("Error Condition: %s, Additional Response: %s",
											responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
									Thread.sleep(10000); // wait for 10 seconds before next iteration
									buildAndSendRequestMessage = true;
								} else {
									log(String.format("Error Condition: %s, Additional Response: %s",
											responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
									gotValidResponse = true;
									waitingForResponse = false;
								}
							}
						}
					}
				}
			} catch (ConfigurationException e) {
				log(String.format("ConfigurationException: %s", e.toString()));
			} catch (NotConnectedException e) {
				log(String.format("NotConnectedException: %s", e.toString()));
			}
			return gotValidResponse;
		});
		try {
			transaction.get(90, TimeUnit.SECONDS); // set timeout
		} catch (TimeoutException e) {
			System.err.println("Transaction Status Timeout...");
		} catch (ExecutionException | InterruptedException e) {
			log(String.format("Exception: %s", e.toString()));
		} finally {
			executor.shutdownNow();
		}
	}

	private static SaleToPOIRequest buildLoginRequest() throws ConfigurationException {
		// Login Request
		SaleSoftware saleSoftware = new SaleSoftware.Builder()//
				.providerIdentification(SaleSystemConfig.getInstance().getProviderIdentification())//
				.applicationName(SaleSystemConfig.getInstance().getApplicationName())//
				.softwareVersion(SaleSystemConfig.getInstance().getSoftwareVersion())//
				.certificationCode(SaleSystemConfig.getInstance().getCertificationCode())//
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
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, loginRequest,
					KEKConfig.getInstance().getValue());
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

	private static SaleToPOIRequest buildPaymentRequest(String serviceID) throws ConfigurationException {
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
				.requestedAmount(new BigDecimal(1.00))//
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
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, paymentRequest,
					KEKConfig.getInstance().getValue());
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

	private static SaleToPOIRequest buildAbortRequest(String paymentServiceID, String abortReason)
			throws ConfigurationException {

		// Message Header
		MessageHeader messageHeader = new MessageHeader.Builder()//
				.messageClass(MessageClass.Service)//
				.messageCategory(MessageCategory.Abort)//
				.messageType(MessageType.Request)//
				.serviceID(MessageHeaderUtil.generateServiceID(10))//
				.saleID(SALE_ID)//
				.POIID(POI_ID)//
				.build();

		MessageReference messageReference = new MessageReference.Builder().messageCategory(MessageCategory.Abort)
				.serviceID(paymentServiceID).build();

		AbortTransactionRequest abortTransactionRequest = new AbortTransactionRequest(messageReference, abortReason);

		SecurityTrailer securityTrailer = null;
		try {
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, abortTransactionRequest,
					KEKConfig.getInstance().getValue());
		} catch (InvalidKeyException | NoSuchPaddingException | InvalidAlgorithmParameterException
				| NoSuchAlgorithmException | IllegalBlockSizeException | BadPaddingException | InvalidKeySpecException
				| IOException e) {
			e.printStackTrace();
		}

		SaleToPOIRequest saleToPOI = new SaleToPOIRequest.Builder()//
				.messageHeader(messageHeader)//
				.request(abortTransactionRequest)//
				.securityTrailer(securityTrailer)//
				.build();

		return saleToPOI;
	}

	private static SaleToPOIRequest buildTransactionStatusRequest(String serviceID) throws ConfigurationException {
		// Transaction Status Request
		MessageReference messageReference = new MessageReference.Builder()//
				.messageCategory(MessageCategory.Payment)//
				.POIID(POI_ID)//
				.saleID(SALE_ID)//
				.serviceID(serviceID)//
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
			securityTrailer = SecurityTrailerUtil.generateSecurityTrailer(messageHeader, transactionStatusRequest,
					KEKConfig.getInstance().getValue());
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

	private static void log(String logData) {
		System.out.println(sdf.format(new Timestamp(System.currentTimeMillis())) + " " + logData); // 2021.03.24.16.34.26
	}
}
