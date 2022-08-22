package com.dmg.fusion;

import au.com.dmg.fusion.MessageHeader;
import au.com.dmg.fusion.client.FusionClient;
import au.com.dmg.fusion.config.FusionClientConfig;
import au.com.dmg.fusion.config.KEKConfig;
import au.com.dmg.fusion.config.SaleSystemConfig;
import au.com.dmg.fusion.data.*;
import au.com.dmg.fusion.exception.NotConnectedException;
import au.com.dmg.fusion.request.SaleTerminalData;
import au.com.dmg.fusion.request.SaleToPOIRequest;
import au.com.dmg.fusion.request.loginrequest.LoginRequest;
import au.com.dmg.fusion.request.loginrequest.SaleSoftware;
import au.com.dmg.fusion.request.paymentrequest.*;
import au.com.dmg.fusion.request.transactionstatusrequest.MessageReference;
import au.com.dmg.fusion.request.transactionstatusrequest.TransactionStatusRequest;
import au.com.dmg.fusion.response.Response;
import au.com.dmg.fusion.response.ResponseResult;
import au.com.dmg.fusion.response.SaleToPOIResponse;
import au.com.dmg.fusion.securitytrailer.SecurityTrailer;
import au.com.dmg.fusion.util.MessageHeaderUtil;
import au.com.dmg.fusion.util.SecurityTrailerUtil;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.naming.ConfigurationException;
import javax.websocket.DeploymentException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.spec.InvalidKeySpecException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.*;

public class FusionClientDemo {

	public static final String SALE_ID;
	public static final String POI_ID;
	private static final FusionClient fusionClient = new FusionClient();

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
			System.out.println("Disconnected from websocket server");
		} catch (ConfigurationException e) {
			System.out.println(e);
		} catch (KeyManagementException | NoSuchAlgorithmException | DeploymentException | IOException
				| URISyntaxException | CertificateException | KeyStoreException e) {
			System.out.println(e);
		}
	}
	
	private static void initConfig() {
		String ENV = "DEV"; // test environment only - replace for production
		String serverDomain = "wss://www.cloudposintegration.io/nexodev"; // test environment only - replace for production
		String socketProtocol = "TLSv1.2";

		String kekValue = "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21"; // test environment only - replace for production
		String keyIdentifier = "SpecV2TestMACKey"; // test environment only - replace for production
		String keyVersion = "20191122164326.594"; // test environment only - replace for production

		String providerIdentification = "Company A"; // test environment only - replace for production
		String applicationName = "POS Retail"; // test environment only - replace for production
		String softwareVersion = "01.00.00"; // test environment only - replace for production
		String certificationCode = "98cf9dfc-0db7-4a92-8b8cb66d4d2d7169"; // test environment only - replace for production

		try {
			FusionClientConfig.init(serverDomain, socketProtocol, ENV);
			KEKConfig.init(kekValue, keyIdentifier, keyVersion);
			SaleSystemConfig.init(providerIdentification, applicationName, softwareVersion, certificationCode);
		} catch (ConfigurationException e) {
			System.out.println(e); // Ensure all config fields have values
		}
	}

	private static void doLogin() {
		SaleToPOIRequest loginRequest = null;
		try {
			loginRequest = buildLoginRequest();

			System.out.println("Sending message to websocket server: " + "\n" + loginRequest);
			fusionClient.sendMessage(loginRequest);

			boolean waitingForResponse = true;

			while (waitingForResponse) {
				Optional<SaleToPOIResponse> optResponse = null;
				try {
					optResponse = Optional.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));
				} catch (InterruptedException e) {
					System.out.println(e);
				}

				if (optResponse.isPresent()) {
					SaleToPOIResponse response = optResponse.get();
					System.out.println(response.toJson());

					boolean serviceIDMatchesRequest = response.getMessageHeader() != null //
							&& response.getMessageHeader().getServiceID()
									.equalsIgnoreCase(loginRequest.getMessageHeader().getServiceID());

					if (serviceIDMatchesRequest && response.getLoginResponse() != null //
							&& response.getLoginResponse().getResponse() != null) {
						Response responseBody = response.getLoginResponse().getResponse();

						if (responseBody.getResult() != null) {
							System.out.println(String.format("Login Result: %s ", responseBody.getResult()));

							if (responseBody.getResult() != ResponseResult.Success) {
								System.out.println(String.format("Error Condition: %s, Additional Response: %s",
										responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
							}
						}
						waitingForResponse = false;
					}
				}
			}
		} catch (ConfigurationException e) {
			System.out.println(e);
		} catch (NotConnectedException e) {
			System.out.println(e);
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
						optMessageRequest.ifPresent(o -> System.out.println(o.toString()));
					} catch (InterruptedException e) {
						System.out.println("Stop polling for display requests...");
						break;
					}
				}
			}
		});

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Void> payment = executor.submit(() -> {
			SaleToPOIRequest paymentRequest = null;

			// Payment request
			try {
				paymentRequest = buildPaymentRequest(serviceID);

				System.out.println("Sending message to websocket server: " + "\n" + paymentRequest);
				fusionClient.sendMessage(paymentRequest);

				boolean waitingForResponse = true;
				displayRequestThread.start();

				while (waitingForResponse) {
					Optional<SaleToPOIResponse> optResponse = Optional
							.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));

					if (optResponse.isPresent()) {
						SaleToPOIResponse response = optResponse.get();
						System.out.println(response.toJson());

						boolean serviceIDMatchesRequest = response.getMessageHeader() != null //
								&& response.getMessageHeader().getServiceID()
										.equalsIgnoreCase(paymentRequest.getMessageHeader().getServiceID());

						if (serviceIDMatchesRequest && response.getPaymentResponse().getResponse() != null) {
							boolean saleTransactionIDMatchesRequest = response.getPaymentResponse()
									.getSaleData() != null
									&& response.getPaymentResponse().getSaleData().getSaleTransactionID() != null
									&& response.getPaymentResponse().getSaleData().getSaleTransactionID()
											.getTransactionID().equals(paymentRequest.getPaymentRequest().getSaleData()
													.getSaleTransactionID().getTransactionID());

							if (!saleTransactionIDMatchesRequest) {
								if (response.getPaymentResponse().getSaleData() == null) {
									System.out.println("Sale ID missing from request");
								} else {
									System.out.println("Unknown sale ID " + response.getPaymentResponse().getSaleData()
											.getSaleTransactionID().getTransactionID());
								}
								break;
							}

							Response responseBody = response.getPaymentResponse().getResponse();

							if (responseBody.getResult() != null) {
								System.out.println(String.format("Payment Result: %s", responseBody.getResult()));

								if (responseBody.getResult() != ResponseResult.Success) {
									System.out.println(String.format("Error Condition: %s, Additional Response: %s",
											responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
								}
							}

							waitingForResponse = false;
						}
					}
				}
			} catch (ConfigurationException e) {
				System.out.println(e);
			} catch (NotConnectedException e) {
				System.out.println(e);
			} finally {
				displayRequestThread.interrupt();
			}

			return null;
		});

		try {
			System.out.println(payment.get(60, TimeUnit.SECONDS)); // set timeout
		} catch (TimeoutException e) {
			System.err.println("Payment Request Timeout...");
		} catch (ExecutionException | InterruptedException e) {
			System.out.println(e);
		} finally {
			executor.shutdownNow();
			checkTransactionStatus(serviceID);
		}
	}

	private static void checkTransactionStatus(String serviceID) {
		System.out.println("Sending transaction status request to check status of payment...");

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Void> transaction = executor.submit(() -> {
			SaleToPOIRequest transactionStatusRequest = null;
			try {
				transactionStatusRequest = buildTransactionStatusRequest(serviceID);

				System.out.println("Sending message to websocket server: " + "\n" + transactionStatusRequest);
				fusionClient.sendMessage(transactionStatusRequest);

				boolean waitingForResponse = true;

				while (waitingForResponse) {
					Optional<SaleToPOIResponse> optResponse = Optional
							.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));

					if (optResponse.isPresent()) {
						SaleToPOIResponse response = optResponse.get();
						System.out.println(response.toJson());

						boolean serviceIDMatchesRequest = response.getMessageHeader() != null
								&& response.getMessageHeader().getServiceID()
										.equalsIgnoreCase(transactionStatusRequest.getMessageHeader().getServiceID());

						if (serviceIDMatchesRequest && response.getTransactionStatusResponse() != null
								&& response.getTransactionStatusResponse().getResponse() != null) {
							Response responseBody = response.getTransactionStatusResponse().getResponse();

							if (responseBody.getResult() != null) {
								System.out.println(
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
										System.out.println(
												String.format("Payment Result: %s", paymentResponseBody.getResult()));

										if (paymentResponseBody.getErrorCondition() != null
												|| paymentResponseBody.getAdditionalResponse() != null) {
											System.out.println(
													String.format("Error Condition: %s, Additional Response: %s",
															responseBody.getErrorCondition(),
															responseBody.getAdditionalResponse()));
										}
									}
									waitingForResponse = false;

								} else if (responseBody.getErrorCondition() == ErrorCondition.InProgress) {
									System.out.println("Payment in progress...");
									System.out.println(String.format("Error Condition: %s, Additional Response: %s",
											responseBody.getErrorCondition(), responseBody.getAdditionalResponse()));
									Thread.sleep(10000); // wait for 10 seconds before next iteration
								} else {
									waitingForResponse = false;
								}
							}
						}
					}
				}
			} catch (ConfigurationException e) {
				System.out.println(e);
			} catch (NotConnectedException e) {
				System.out.println(e);
			}
			return null;
		});

		try {
			System.out.println(transaction.get(60, TimeUnit.SECONDS)); // set timeout
		} catch (TimeoutException e) {
			System.err.println("Transaction Status Timeout...");
		} catch (ExecutionException | InterruptedException e) {
			System.out.println(e);
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

}
