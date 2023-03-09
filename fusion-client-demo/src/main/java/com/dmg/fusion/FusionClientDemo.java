package com.dmg.fusion;

import au.com.dmg.fusion.client.FusionClient;
import au.com.dmg.fusion.config.FusionClientConfig;
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
import au.com.dmg.fusion.util.MessageHeaderUtil;

import javax.naming.ConfigurationException;
import javax.websocket.DeploymentException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URISyntaxException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.Arrays;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.*;

public class FusionClientDemo {
	//UPDATE TO CHANGE ENVIRONMENT
	public static Boolean isTestEnvironment = true;

	public static String SALE_ID;
	public static String POI_ID;
	public static FusionClientConfig fusionClientConfig;

	static {
		fusionClientConfig = new FusionClientConfig(isTestEnvironment);
	}

	private static FusionClient fusionClient = new FusionClient();

	public static void main(String[] args) {
			// This will connect to the websocket
			initConfig();

			doLogin();
			doPayment();

		try {
			fusionClient.disconnect();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

	}
	
	private static void initConfig() {
		SALE_ID = isTestEnvironment ? "VA POS"  : "DMGProductionVerificationTest2";
		POI_ID = isTestEnvironment ? "DMGVA001" : "E3330010";

		fusionClientConfig.saleID = SALE_ID;
		fusionClientConfig.poiID = POI_ID;
		fusionClientConfig.providerIdentification = isTestEnvironment ? "Company A" : "H_L";
		fusionClientConfig.applicationName = isTestEnvironment ? "POS Retail" : "Exceed";
		fusionClientConfig.softwareVersion = isTestEnvironment ? "01.00.00" : "9.0.0.0";
		fusionClientConfig.certificationCode = isTestEnvironment ? "98cf9dfc-0db7-4a92-8b8cb66d4d2d7169" : "01c99f18-7093-4d77-b6f6-2c762c8ed698";

		fusionClientConfig.kekValue = isTestEnvironment ? "44DACB2A22A4A752ADC1BBFFE6CEFB589451E0FFD83F8B21" : "ba92ab29e9918943167325f4ea1f5d9b5ee679ea89a82f2c";
		try {
			fusionClient.init(fusionClientConfig);
		} catch (ConfigurationException | DeploymentException | CertificateException | URISyntaxException |
				 IOException | NoSuchAlgorithmException | KeyStoreException | KeyManagementException e) {
			throw new RuntimeException(e);
		}
	}

	private static void doLogin() {
		String currentServiceID = MessageHeaderUtil.generateServiceID(10);
		LoginRequest loginRequest = null;
		try {
			loginRequest = buildLoginRequest();

			System.out.println("Sending message to websocket server: " + "\n" + loginRequest);
			fusionClient.sendMessage(loginRequest, currentServiceID);

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
									.equalsIgnoreCase(currentServiceID);

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
			PaymentRequest paymentRequest = null;

			// Payment request
			try {
				paymentRequest = buildPaymentRequest();

				System.out.println("Sending message to websocket server: " + "\n" + paymentRequest);
				fusionClient.sendMessage(paymentRequest, serviceID);

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
										.equalsIgnoreCase(serviceID);

						if (serviceIDMatchesRequest && response.getPaymentResponse().getResponse() != null) {
							boolean saleTransactionIDMatchesRequest = response.getPaymentResponse()
									.getSaleData() != null
									&& response.getPaymentResponse().getSaleData().getSaleTransactionID() != null
									&& response.getPaymentResponse().getSaleData().getSaleTransactionID()
											.getTransactionID().equals(paymentRequest.getSaleData()
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

		String newServiceID = MessageHeaderUtil.generateServiceID(10);

		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Void> transaction = executor.submit(() -> {
			TransactionStatusRequest transactionStatusRequest = null;
			try {
				transactionStatusRequest = buildTransactionStatusRequest(serviceID);

				System.out.println("Sending message to websocket server: " + "\n" + transactionStatusRequest);
				fusionClient.sendMessage(transactionStatusRequest, newServiceID);

				boolean waitingForResponse = true;

				while (waitingForResponse) {
					Optional<SaleToPOIResponse> optResponse = Optional
							.ofNullable(fusionClient.inQueueResponse.poll(1, TimeUnit.SECONDS));

					if (optResponse.isPresent()) {
						SaleToPOIResponse response = optResponse.get();
						System.out.println(response.toJson());

						boolean serviceIDMatchesRequest = response.getMessageHeader() != null
								&& response.getMessageHeader().getServiceID()
										.equalsIgnoreCase(newServiceID);

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

	private static LoginRequest buildLoginRequest() throws ConfigurationException {
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

		return loginRequest;
	}

	private static PaymentRequest buildPaymentRequest() throws ConfigurationException {
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
				.requestedAmount(new BigDecimal(100.00))//
				.build();

		SaleItem saleItem = new SaleItem.Builder()//
				.itemID(0)//
				.productCode("productCode")//
				.unitOfMeasure(UnitOfMeasure.Other)//
				.quantity(new BigDecimal(1))//
				.unitPrice(new BigDecimal(100.00))//
				.itemAmount(new BigDecimal(100.00))//
				.productLabel("Product Label")//
//				.addCustomField(new CustomField.Builder()
//						.key("customFieldkey")
//						.type(CustomFieldType.string)
//						.value("customFieldkeyValue")
//						.build())
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

		return paymentRequest;
	}

	private static TransactionStatusRequest buildTransactionStatusRequest(String serviceID) throws ConfigurationException {
		// Transaction Status Request
		MessageReference messageReference = new MessageReference.Builder()//
				.messageCategory(MessageCategory.Payment)//
				.POIID(POI_ID)//
				.saleID(SALE_ID)//
				.serviceID(serviceID)//
				.build();

		TransactionStatusRequest transactionStatusRequest = new TransactionStatusRequest(messageReference);

		return transactionStatusRequest;
	}

}
