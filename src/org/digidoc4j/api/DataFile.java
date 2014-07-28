package org.digidoc4j.api;

import eu.europa.ec.markt.dss.DSSUtils;
import eu.europa.ec.markt.dss.Digest;
import eu.europa.ec.markt.dss.DigestAlgorithm;
import eu.europa.ec.markt.dss.signature.*;
import org.digidoc4j.api.exceptions.DigiDoc4JException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Data file wrapper providing methods for handling signed files or files to be signed in Container.
 */
public class DataFile {
  final Logger logger = LoggerFactory.getLogger(DataFile.class);


  DSSDocument document = null;
  private Digest digest = null;

  /**
   * Creates container.
   *
   * @param path     file name with path
   * @param mimeType MIME type of the data file, for example 'text/plain' or 'application/msword'
   */
  public DataFile(String path, String mimeType) {
    try {
      document = new FileDocument(path);
      document.setMimeType(getMimeType(mimeType));
    } catch (Exception e) {
      logger.info(e.getMessage());
      throw new DigiDoc4JException(e);
    }
  }

  /**
   * Creates in memory document container.
   *
   * @param data     file content
   * @param fileName file name with path
   * @param mimeType MIME type of the data file, for example 'text/plain' or 'application/msword'
   */
  public DataFile(byte[] data, String fileName, String mimeType) {
    ByteArrayInputStream stream = new ByteArrayInputStream(data);
    document = new InMemoryDocument(stream, fileName, getMimeType(mimeType));
  }

  /**
   * Creates in streamed document container.
   *
   * @param stream   file content from stream
   * @param fileName file name with path
   * @param mimeType MIME type of the stream file, for example 'text/plain' or 'application/msword'
   */
  public DataFile(InputStream stream, String fileName, String mimeType) {
    try {
      document = new StreamDocument(stream, fileName, getMimeType(mimeType));
    } catch (Exception e) {
      logger.info(e.getMessage());
      throw new DigiDoc4JException(e);
    }
  }

  private MimeType getMimeType(String mimeType) {
    MimeType mimeTypeCode = MimeType.fromCode(mimeType);
    if (mimeTypeCode == null) {
      DigiDoc4JException exception = new DigiDoc4JException("Unknown mime type");
      logger.info(exception.toString());
      throw exception;
    }
    return mimeTypeCode;
  }

  /**
   * Calculates digest http://www.w3.org/2001/04/xmlenc#sha256 for the data file.
   * If the digest has already been calculated it will return it, otherwise it calculates the digest.
   * <p/>
   *
   * @return calculated digest
   * @throws Exception thrown if the file does not exist or the digest calculation fails.
   */
  public byte[] calculateDigest() throws Exception {
    return calculateDigest(new URL("http://www.w3.org/2001/04/xmlenc#sha256"));
  }

  /**
   * Calculates digest for data file. If digest is already calculated returns it, otherwise calculates the digest.
   * <p>Supported uris for BDoc:</p>
   * <br>http://www.w3.org/2000/09/xmldsig#sha1
   * <br>http://www.w3.org/2001/04/xmldsig-more#sha224
   * <br>http://www.w3.org/2001/04/xmlenc#sha256
   * <br>http://www.w3.org/2001/04/xmldsig-more#sha384
   * <br>http://www.w3.org/2001/04/xmlenc#sha512
   * <p>In case of DDoc files the parameter is ignored and SHA1 hash is always returned</p>
   *
   * @param method method uri for calculating the digest
   * @return calculated digest
   */
  public byte[] calculateDigest(URL method) {        // TODO exceptions to throw
    if (digest == null) {
      DigestAlgorithm digestAlgorithm = DigestAlgorithm.forXML(method.toString());
      digest = new Digest(digestAlgorithm, calculateDigestInternal(digestAlgorithm));
    }
    return digest.getValue();
  }

  byte[] calculateDigestInternal(DigestAlgorithm digestAlgorithm) {
    return DSSUtils.digest(digestAlgorithm, document.getBytes());
  }

  /**
   * Returns the data file name.
   *
   * @return filename
   */
  public String getFileName() {
    return new File(document.getName()).getName();
  }

  /**
   * Returns the data file size.
   *
   * @return file size
   */
  public long getFileSize() {
    if (document instanceof StreamDocument) {
      try {
        return Files.size(Paths.get(document.getAbsolutePath()));
      } catch (IOException e) {
        logger.info(e.getMessage());
        throw new DigiDoc4JException(e);
      }
    }
    return document.getBytes().length;
  }

  /**
   * Returns the file media type.
   *
   * @return media type
   */
  public String getMediaType() {
    return document.getMimeType().getCode();
  }

  /**
   * Saves a copy of the data file as a file to the specified stream.
   *
   * @param out stream where data is written to
   * @throws java.io.IOException on file write error
   */
  public void saveAs(OutputStream out) throws IOException {
    out.write(document.getBytes());
  }

  /**
   * Saves a copy of the data file as a file with the specified file name.
   *
   * @param path full file path where the data file should be saved to. If the file exists it will be overwritten
   */
  //TODO exception - method throws DSSException which can be caused by other exceptions
  public void saveAs(String path) {
    document.save(path);
  }

  /**
   * Gives file bytes
   *
   * @return data as bytes
   */
  public byte[] getBytes() {
    return document.getBytes();
  }

  /**
   * Gives data file as stream
   *
   * @return data file stream
   */
  public InputStream getStream() {
    return document.openStream();
  }
}
