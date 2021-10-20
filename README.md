# fusioncloud-sdk-java-demo

### Overview

***

This app demonstrates how to send login, payment and transaction status requests using the Fusion Cloud SDK.

### Getting Started

***

##### Downloading Dependencies
Run 'mvn install' from the root directory of the project. This will download the dependencies from the maven repository.

##### Configuration
The following configuration classes must be initialized with the appropriate values during start up (otherwise a `ConfigurationException` will be thrown when an instance of any of these classes is called):

`FusionClientConfig`
 - certificateLocation (root CA location e.g., 'src/main/resources/root.crt')
 - serverDomain (domain/server URI)
 - socketProtocol (defaults to 'TLSv1.2' if not provided)

`KEKConfig`
 - value (KEK provided by DataMesh)
 - keyIdentifier (SpecV2TestMACKey or SpecV2ProdMACKey)
 - keyVersion (version)

`SaleSystemConfig` (static sale system settings - provided by DataMesh)
 - providerIdentification
 - applicationName
 - softwareVersion
 - certificationCode
 
In this demo app, the initialization of the configuration classes is done inside the `initConfig()` method. The fields must be updated with the correct values before running the app.

### Dependencies

***

This project uses the following dependencies:  

- **[Java Fusion SDK](https://github.com/datameshgroup/fusionsatellite-sdk-java):** contains all the models necessary to create request and response messages to the Fusion websocket server
- **[Java Fusion Cloud SDK](https://github.com/datameshgroup/fusioncloud-sdk-java):** contains a websocket client and security components needed to communicate with Unify.

### Minimum Required JDK

***

- Java 1.8

> **Note:** Other versions may work as well, but have not been tested.

