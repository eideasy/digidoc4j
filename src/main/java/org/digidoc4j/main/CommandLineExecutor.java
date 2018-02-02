/* DigiDoc4J library
*
* This software is released under either the GNU Library General Public
* License (see LICENSE.LGPL).
*
* Note that the only valid version of the LGPL license as far as this
* project is concerned is the original GNU Library General Public License
* Version 2.1, February 1999
*/

package org.digidoc4j.main;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.digidoc4j.Container;
import org.digidoc4j.ContainerBuilder;
import org.digidoc4j.ContainerOpener;
import org.digidoc4j.DataFile;
import org.digidoc4j.EncryptionAlgorithm;
import org.digidoc4j.Signature;
import org.digidoc4j.SignatureBuilder;
import org.digidoc4j.SignatureProfile;
import org.digidoc4j.SignatureToken;
import org.digidoc4j.ValidationResult;
import org.digidoc4j.exceptions.DigiDoc4JException;
import org.digidoc4j.impl.asic.AsicContainer;
import org.digidoc4j.impl.asic.asics.AsicSContainer;
import org.digidoc4j.impl.pades.PadesContainer;
import org.digidoc4j.signers.PKCS11SignatureToken;
import org.digidoc4j.signers.PKCS12SignatureToken;
import org.digidoc4j.signers.TimestampToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import eu.europa.esig.dss.DSSUtils;
import eu.europa.esig.dss.DigestAlgorithm;

/**
 * Class for managing digidoc4j-util parameters.
 */
public class CommandLineExecutor {

  private final Logger log = LoggerFactory.getLogger(CommandLineExecutor.class);

  private final ExecutionContext context;
  private boolean fileHasChanged;

  public CommandLineExecutor(ExecutionContext context) {
    this.context = context;
  }

  public void processContainer(Container container) {
    this.log.debug("Processing container");
    if (container instanceof PadesContainer) {
      this.verifyPadesContainer(container);
    } else {
      this.manipulateContainer(container);
      if (Container.DocumentType.ASICS.equals(this.getContainerType()) && this.isOptionsToSignAndAddFile()) {
        AsicSContainer asicSContainer = (AsicSContainer) container;
        this.verifyIfAllowedToAddSignature(asicSContainer);
        this.signAsicSContainer(asicSContainer);
      } else {
        this.signContainer(container);
      }
      this.verifyContainer(container);
    }
  }

  public boolean hasCommand() {
    return this.context.getCommand() != null;
  }

  public void executeCommand() {
    if (this.hasCommand()) {
      for (ExecutionOption option : this.context.getCommand().getMandatoryOptions()) {
        switch (option) {
          case IN:
            this.context.setContainer(this.openContainer(this.context.getCommandLine().getOptionValue(option.getName())));
            break;
          case ADD:
            try {
              this.context.getContainer();
            } catch (DigiDoc4JException ignored) {
              this.context.setContainer(this.openContainer());
            }
            this.addData();
            break;
          case CERTIFICATE:
            this.context.setSignatureBuilder(this.createSignatureBuilderWithCertificate());
            break;
          case DIGEST:
            switch (this.context.getCommand()) {
              case EXTERNAL_COMPOSE_DIGEST:
                this.storeDigest();
                break;
              case EXTERNAL_COMPOSE_SIGNATURE_WITH_PKCS11:
              case EXTERNAL_COMPOSE_SIGNATURE_WITH_PKCS12:
                this.context.setDigest(this.loadDigest());
                break;
            }
            break;
          case PKCS11:
            this.context.setSignatureToken(this.loadPKCS11Token());
            break;
          case PKCS12:
            this.context.setSignatureToken(this.loadPKCS12Token());
            break;
          case SIGNAURE:
            switch (this.context.getCommand()) {
              case EXTERNAL_COMPOSE_SIGNATURE_WITH_PKCS11:
              case EXTERNAL_COMPOSE_SIGNATURE_WITH_PKCS12:
                this.createAndStoreSignature();
                break;
              case EXTERNAL_ADD_SIGNATURE:
                this.context.setSignature(this.loadSignature());
            }
            break;
          case EXTERNAL:
            break;
          default:
            this.log.warn("No option <{}> implemented", option);
        }
      }
      this.postExecutionProcess();
    } else {
      throw new DigiDoc4JException("No command to execute");
    }
  }

  /**
   * Gets container type for util logic
   *
   * @return
   */
  public Container.DocumentType getContainerType() {
    if (StringUtils.equalsIgnoreCase(this.context.getCommandLine().getOptionValue("type"), "BDOC"))
      return Container.DocumentType.BDOC;
    if (StringUtils.equalsIgnoreCase(this.context.getCommandLine().getOptionValue("type"), "ASICS"))
      return Container.DocumentType.ASICS;
    if (StringUtils.equalsIgnoreCase(this.context.getCommandLine().getOptionValue("type"), "ASICE"))
      return Container.DocumentType.ASICE;
    if (StringUtils.equalsIgnoreCase(this.context.getCommandLine().getOptionValue("type"), "DDOC"))
      return Container.DocumentType.DDOC;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".bdoc"))
      return Container.DocumentType.BDOC;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".asics"))
      return Container.DocumentType.ASICS;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".scs"))
      return Container.DocumentType.ASICS;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".asice"))
      return Container.DocumentType.ASICE;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".sce"))
      return Container.DocumentType.ASICE;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".ddoc"))
      return Container.DocumentType.DDOC;
    if (StringUtils.endsWithIgnoreCase(this.context.getCommandLine().getOptionValue("in"), ".pdf"))
      return Container.DocumentType.PADES;
    return Container.DocumentType.BDOC;
  }

  public Container openContainer() {
    return this.openContainer(null);
  }

  public Container openContainer(String containerPath) {
    Container.DocumentType type = this.getContainerType();
    if (new File(containerPath).exists() || this.context.getCommandLine().hasOption("verify") || this.context.getCommandLine().hasOption("remove")) {
      this.log.debug("Opening container " + containerPath);
      return ContainerOpener.open(containerPath);
    } else {
      this.log.debug("Creating new " + type + "container " + containerPath);
      return ContainerBuilder.aContainer(type.name()).build();
    }
  }

  public void saveContainer(Container container, String containerPath) {
    if (this.fileHasChanged) {
      container.saveAsFile(containerPath);
      if (new File(containerPath).exists()) {
        this.log.debug("Container has been successfully saved to " + containerPath);
      } else {
        this.log.warn("Container was NOT saved to " + containerPath);
      }
    }
  }

  /*
   * RESTRICTED METHODS
   */

  private void verifyIfAllowedToAddSignature(AsicSContainer asicSContainer) {
    if (asicSContainer.isTimestampTokenDefined()) {
      throw new DigiDoc4JException("This container has already timestamp. Should be no signatures in case of timestamped ASiCS container.");
    }
    if (!asicSContainer.getSignatures().isEmpty()) {
      throw new DigiDoc4JException("This container is already signed. Should be only one signature in case of ASiCS container.");
    }
  }

  private boolean isOptionsToSignAndAddFile() {
    return this.context.getCommandLine().hasOption("add") || this.context.getCommandLine().hasOption("pkcs11") || this.context.getCommandLine().hasOption("pkcs12");
  }

  private void signAsicSContainer(AsicSContainer asicSContainer) {
    if (this.context.getCommandLine().hasOption("tst")) {
      this.signContainerWithTst(asicSContainer);
    } else {
      this.signContainer(asicSContainer);
    }
  }

  private void manipulateContainer(Container container) {
    if (this.context.getCommandLine().hasOption(ExecutionOption.ADD.getName())) {
      this.addData(container);
    }
    if (this.context.getCommandLine().hasOption("remove")) {
      container.removeDataFile(this.context.getCommandLine().getOptionValue("remove"));
      this.fileHasChanged = true;
    }
    if (this.context.getCommandLine().hasOption(ExecutionOption.EXTERNAL.getName())) {
      this.log.debug("Extracting data file");
      this.extractDataFile(container);
    }
  }

  private void addData() {
    this.addData(this.context.getContainer());
  }

  private void addData(Container container) {
    this.log.debug("Adding data to container ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.ADD.getName());
    container.addDataFile(values[0], values[1]);
    this.fileHasChanged = true;
  }

  private X509Certificate loadCertificate() {
    this.log.debug("Loading certificate ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.ADD.getName());
    try (InputStream stream = new FileInputStream(values[0])) {
      return DSSUtils.loadCertificate(stream).getCertificate();
    } catch (IOException e) {
      throw new DigiDoc4JException(String.format("Unable to load certificate file from <%s>", values[0]), e);
    }
  }

  private byte[] loadDigest() {
    this.log.debug("Loading digest ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.DIGEST.getName());
    try {
      return Files.readAllBytes(Paths.get(values[0]));
    } catch (IOException e) {
      throw new DigiDoc4JException(String.format("Unable to load digest file from <%s>", values[0]), e);
    }
  }

  private byte[] loadSignature() {
    this.log.debug("Loading digest ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.SIGNAURE.getName());
    try {
      return Files.readAllBytes(Paths.get(values[0]));
    } catch (IOException e) {
      throw new DigiDoc4JException(String.format("Unable to load signature file from <%s>", values[0]), e);
    }
  }

  private SignatureToken loadPKCS11Token() {
    this.log.debug("Loading PKCS11 token ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.PKCS11.getName());
    try {
      return new PKCS11SignatureToken(values[0], values[1].toCharArray(), Integer.parseInt(values[2]));
    } catch (Exception e) {
      throw new DigiDoc4JException(String.format("Unable to load PKCS11 token <%s, %s, %s>", values[0], values[1], values[2]));
    }
  }

  private SignatureToken loadPKCS12Token() {
    this.log.debug("Loading PKCS12 token ...");
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.PKCS12.getName());
    try {
      return new PKCS12SignatureToken(values[0], values[1].toCharArray());
    } catch (Exception e) {
      throw new DigiDoc4JException(String.format("Unable to load PKCS12 token <%s, %s>", values[0], values[1]));
    }
  }

  private SignatureBuilder createSignatureBuilderWithCertificate() {
    return SignatureBuilder.aSignature(this.context.getContainer()).withSigningCertificate(this.loadCertificate());
  }

  private void createAndStoreSignature() {
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.SIGNAURE.getName());
    try (OutputStream stream = new FileOutputStream(values[0])) {
      IOUtils.write(this.context.getSignatureToken().sign(this.context.getDigestAlgorithm(), this.context.getDigest()), stream);
    } catch (IOException e) {
      throw new DigiDoc4JException(String.format("Unable to store signature file to <%s>", values[0]), e);
    }
  }

  private void storeDigest() {
    String[] values = this.context.getCommandLine().getOptionValues(ExecutionOption.DIGEST.getName());
    try (OutputStream stream = new FileOutputStream(values[0])) {
      IOUtils.write(this.context.getSignatureBuilder().withSignatureDigestAlgorithm(this.context.getDigestAlgorithm())
          .buildDataToSign().getDataToSign(), stream);
    } catch (IOException e) {
      throw new DigiDoc4JException(String.format("Unable to store digest file to <%s>", values[0]), e);
    }
  }

  private void storeContainerWithSignature() {
    this.log.debug("Adding signature to container ...");
    Container container = this.context.getContainer();
    container.addSignature(SignatureBuilder.aSignature(container).withSignatureDigestAlgorithm(this.context.getDigestAlgorithm())
        .buildDataToSign().finalize(this.context.getSignature()));
    this.fileHasChanged = true;
    this.saveContainer(container, this.context.getCommandLine().getOptionValue(ExecutionOption.IN.getName()));
  }

  private void postExecutionProcess() {
    switch (this.context.getCommand()) {
      case EXTERNAL_ADD_SIGNATURE:
        this.storeContainerWithSignature();
        break;
    }
  }

  private void extractDataFile(Container container) {
    String[] optionValues = this.context.getCommandLine().getOptionValues(ExecutionOption.EXTERNAL.getName());
    String fileNameToExtract = optionValues[0];
    String extractPath = optionValues[1];
    boolean fileFound = false;
    for (DataFile dataFile : container.getDataFiles()) {
      if (StringUtils.equalsIgnoreCase(fileNameToExtract, dataFile.getName())) {
        this.log.info("Extracting " + dataFile.getName() + " to " + extractPath);
        dataFile.saveAs(extractPath);
        fileFound = true;
      }
    }
    if (!fileFound) {
      throw new DigiDoc4JUtilityException(4, "Data file " + fileNameToExtract + " was not found in the container");
    }
  }

  private void signContainer(Container container) {
    SignatureBuilder signatureBuilder = SignatureBuilder.aSignature(container);
    this.updateProfile(signatureBuilder);
    this.updateEncryptionAlgorithm(signatureBuilder);
    this.signWithPkcs12(container, signatureBuilder);
    this.signWithPkcs11(container, signatureBuilder);
  }

  private void updateProfile(SignatureBuilder signatureBuilder) {
    if (this.context.getCommandLine().hasOption("profile")) {
      String profile = this.context.getCommandLine().getOptionValue("profile");
      try {
        SignatureProfile signatureProfile = SignatureProfile.valueOf(profile);
        signatureBuilder.withSignatureProfile(signatureProfile);
      } catch (IllegalArgumentException e) {
        System.out.println("Signature profile \"" + profile + "\" is unknown and will be ignored");
      }
    }
  }

  private void updateEncryptionAlgorithm(SignatureBuilder signatureBuilder) {
    if (this.context.getCommandLine().hasOption("encryption")) {
      String encryption = this.context.getCommandLine().getOptionValue("encryption");
      EncryptionAlgorithm encryptionAlgorithm = EncryptionAlgorithm.valueOf(encryption);
      signatureBuilder.withEncryptionAlgorithm(encryptionAlgorithm);
    }
  }

  private void signWithPkcs12(Container container, SignatureBuilder signatureBuilder) {
    if (this.context.getCommandLine().hasOption("pkcs12")) {
      String[] optionValues = this.context.getCommandLine().getOptionValues("pkcs12");
      SignatureToken pkcs12Signer = new PKCS12SignatureToken(optionValues[0], optionValues[1].toCharArray());
      Signature signature = invokeSigning(signatureBuilder, pkcs12Signer);
      container.addSignature(signature);
      this.fileHasChanged = true;
    }
  }

  private void signContainerWithTst(AsicContainer asicContainer) {
    if (this.context.getCommandLine().hasOption("tst") && !(this.context.getCommandLine().hasOption("pkcs12") || this.context.getCommandLine().hasOption("pkcs11"))) {
      DigestAlgorithm digestAlgorithm = DigestAlgorithm.SHA256;
      if (this.context.getCommandLine().hasOption("datst")) {
        String digestAlgorithmStr = this.context.getCommandLine().getOptionValue("datst");
        if (StringUtils.isNotBlank(digestAlgorithmStr)) {
          digestAlgorithm = DigestAlgorithm.forName(digestAlgorithmStr);
        }
      }
      log.info("Digest algorithm to calculate data file hash: " + digestAlgorithm.getName());
      if (this.context.getCommandLine().hasOption("add")) {
        if (asicContainer.getDataFiles().size() > 1) {
          throw new DigiDoc4JException("Data file in container already exists. Should be only one data file in case of ASiCS container.");
        }
        String[] optionValues = this.context.getCommandLine().getOptionValues("add");
        DataFile dataFile = new DataFile(optionValues[0], optionValues[1]);
        DataFile tst = TimestampToken.generateTimestampToken(digestAlgorithm, dataFile);
        asicContainer.setTimeStampToken(tst);
        this.fileHasChanged = true;
      }
    }
  }

  private void signWithPkcs11(Container container, SignatureBuilder signatureBuilder) {
    if (this.context.getCommandLine().hasOption("pkcs11")) {
      String[] optionValues = this.context.getCommandLine().getOptionValues("pkcs11");
      String pkcs11ModulePath = optionValues[0];
      char[] pin = optionValues[1].toCharArray();
      int slotIndex = Integer.parseInt(optionValues[2]);
      SignatureToken pkcs11Signer = new PKCS11SignatureToken(pkcs11ModulePath, pin, slotIndex);
      Signature signature = this.invokeSigning(signatureBuilder, pkcs11Signer);
      container.addSignature(signature);
      this.fileHasChanged = true;
    }
  }

  private Signature invokeSigning(SignatureBuilder signatureBuilder, SignatureToken signatureToken) {
    return signatureBuilder.withSignatureToken(signatureToken).invokeSigning();
  }

  private void verifyContainer(Container container) {
    Path reports = null;
    if (this.context.getCommandLine().hasOption("reportDir")) {
      reports = Paths.get(this.context.getCommandLine().getOptionValue("reportDir"));
    }
    if (this.context.getCommandLine().hasOption("verify")) {
      ContainerVerifier verifier = new ContainerVerifier(this.context.getCommandLine());
      verifier.verify(container, reports);
    }
  }

  private void verifyPadesContainer(Container container) {
    ValidationResult validate = container.validate();
    if (!validate.isValid()) {
      String report = validate.getReport();
      throw new DigiDoc4JException("Pades container has errors" + report);
    } else {
      this.log.info("Container is valid:" + validate.isValid());
    }
  }

  /*
   * ACCESSORS
   */

  public ExecutionContext getContext() {
    return context;
  }

}