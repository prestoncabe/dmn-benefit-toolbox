package org.codeforphilly.bdt.builder.model.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.BadRequestException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Screener {
  private static final int MAX_INTEGRATION_ORIGINS = 20;

  /* Screener metadata */
  private String id;
  private String ownerId;
  private String screenerName;

  /* Screener data */
  private Map<String, Object> formSchema;
  private List<BenefitDetail> benefits;

  /* Publishing properties */
  private String publishedScreenerId;
  private String lastPublishDate;

  /* CRM origins allowed to prefill the published screener and receive its results */
  private List<String> integrationOrigins;

  public Screener(Map<String, Object> model) {
    this.formSchema = model;
  }

  public Screener() {}

  /* Domain creation for POST */
  public static Screener create(String ownerId, String screenerName, String description) {
    Screener s = new Screener();
    s.ownerId = ownerId;
    s.screenerName = screenerName;

    return s;
  }

  /* Screener names are unique per owner, ignoring case and surrounding spaces. */
  public static String normalizeName(String name) {
    return name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
  }

  /*
   * Browsers compare origins in their serialized form (lowercase, no default
   * port, no trailing slash), so store each CRM origin that way.
   */
  public static List<String> normalizeIntegrationOrigins(List<String> origins) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    for (String origin : origins) {
      String value = origin == null ? "" : origin.strip();
      if (!value.isEmpty()) normalized.add(normalizeOrigin(value));
    }
    if (normalized.size() > MAX_INTEGRATION_ORIGINS)
      throw new BadRequestException(
          "Enter at most " + MAX_INTEGRATION_ORIGINS + " CRM origins.");
    return new ArrayList<>(normalized);
  }

  private static String normalizeOrigin(String value) {
    BadRequestException invalid = new BadRequestException(
        "Enter each CRM origin as https://host or https://host:port, without a path: "
            + value);
    URI uri;
    try {
      uri = new URI(value);
    } catch (URISyntaxException e) {
      throw invalid;
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    String path = uri.getRawPath();
    if (value.length() > 200
        || !(scheme.equals("https") || scheme.equals("http"))
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawQuery() != null
        || uri.getRawFragment() != null
        || !(path == null || path.isEmpty() || path.equals("/"))) {
      throw invalid;
    }
    int port = uri.getPort();
    boolean defaultPort = port == -1
        || (scheme.equals("https") && port == 443)
        || (scheme.equals("http") && port == 80);
    return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT)
        + (defaultPort ? "" : ":" + port);
  }

  public Map<String, Object> getFormSchema() {
    return formSchema;
  }

  public void setFormSchema(Map<String, Object> formSchema) {
    this.formSchema = formSchema;
  }

  public void setOwnerId(String ownerId) {
    this.ownerId = ownerId;
  }

  public String getOwnerId() {
    return this.ownerId;
  }

  public void setScreenerName(String screenerName) {
    this.screenerName = screenerName;
  }

  public String getScreenerName() {
    return this.screenerName;
  }

  public void setLastPublishDate(String lastPublishDate) {
    this.lastPublishDate = lastPublishDate;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getId() {
    return this.id;
  }

  public void setPublishedScreenerId(String publishedScreenerId) {
    this.publishedScreenerId = publishedScreenerId;
  }

  public String getPublishedScreenerId() {
    return this.publishedScreenerId;
  }

  public void setLastPublishedDate(String lastPublishDate) {
    this.lastPublishDate = lastPublishDate;
  }

  public String getLastPublishDate() {
    return this.lastPublishDate;
  }

  public List<String> getIntegrationOrigins() {
    return integrationOrigins;
  }

  public void setIntegrationOrigins(List<String> integrationOrigins) {
    this.integrationOrigins = integrationOrigins;
  }

  public List<BenefitDetail> getBenefits() {
    return benefits;
  }

  public void setBenefits(List<BenefitDetail> benefits) {
    this.benefits = benefits;
  }
}
