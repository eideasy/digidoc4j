/* DigiDoc4J library
*
* This software is released under either the GNU Library General Public
* License (see LICENSE.LGPL).
*
* Note that the only valid version of the LGPL license as far as this
* project is concerned is the original GNU Library General Public License
* Version 2.1, February 1999
*/

package org.digidoc4j.impl.bdoc;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.digidoc4j.exceptions.DuplicateDataFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import eu.europa.esig.dss.DSSDocument;
import eu.europa.esig.dss.DSSXMLUtils;

public class ManifestParser implements Serializable {

  private static final Logger logger = LoggerFactory.getLogger(ManifestParser.class);
  public static final String MANIFEST_PATH = "META-INF/manifest.xml";
  private DSSDocument manifestFile;
  private Map<String, ManifestEntry> entries;

  public ManifestParser(DSSDocument manifestFile) {
    this.manifestFile = manifestFile;
  }

  public static ManifestParser findAndOpenManifestFile(List<DSSDocument> detachedContents) {
    DSSDocument manifestFile = findManifestFile(detachedContents);
    return new ManifestParser(manifestFile);
  }

  public boolean containsManifestFile() {
    return manifestFile != null;
  }

  public Map<String, ManifestEntry> getManifestFileItems() {
    if(!containsManifestFile()) {
      return Collections.emptyMap();
    }
    entries = new HashMap<>();
    loadFileEntriesFromManifest();
    return entries;
  }

  private void loadFileEntriesFromManifest() {
    Element root = DSSXMLUtils.buildDOM(manifestFile).getDocumentElement();
    Node firstChild = root.getFirstChild();
    while (firstChild != null) {
      String nodeName = firstChild.getNodeName();
      if ("manifest:file-entry".equals(nodeName)) {
        addFileEntry(firstChild);
      }
      firstChild = firstChild.getNextSibling();
    }
  }

  private void addFileEntry(Node firstChild) {
    NamedNodeMap attributes = firstChild.getAttributes();
    String filePath = attributes.getNamedItem("manifest:full-path").getTextContent();
    String mimeType = attributes.getNamedItem("manifest:media-type").getTextContent();
    if (!"/".equals(filePath)) {
      validateNotDuplicateFile(filePath);
      entries.put(filePath, new ManifestEntry(filePath, mimeType));
    }
  }

  private void validateNotDuplicateFile(String filePath) {
    if(entries.containsKey(filePath)) {
      DuplicateDataFileException digiDoc4JException = new DuplicateDataFileException("duplicate entry in manifest file: " + filePath);
      logger.error(digiDoc4JException.getMessage());
      throw digiDoc4JException;
    }
  }

  private static DSSDocument findManifestFile(List<DSSDocument> detachedContents) {
    for (DSSDocument dssDocument : detachedContents) {
      if (MANIFEST_PATH.equals(dssDocument.getName())) {
        return dssDocument;
      }
    }
    return null;
  }
}
