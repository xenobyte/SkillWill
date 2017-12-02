package com.sinnerschrader.skillwill.services;


import com.sinnerschrader.skillwill.domain.user.User;
import com.sinnerschrader.skillwill.domain.user.UserLdapDetails;
import com.sinnerschrader.skillwill.misc.EmbeddedLdap;
import com.sinnerschrader.skillwill.repositories.userRepository;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.LDAPResult;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Service handling LDAP auth and data retrieval
 *
 * @author torree
 */
@Service
@EnableRetry
public class LdapService {

  private static final Logger logger = LoggerFactory.getLogger(LdapService.class);
  private static LDAPConnection ldapConnection;

  @Value("${ldapUrl}")
  private String ldapUrl;

  @Value("${ldapPort}")
  private int ldapPort;

  @Value("${ldapAuthBaseDN}")
  private String ldapAuthBaseDN;

  @Value("${ldapAuthBaseOUs}")
  private String ldapAuthBaseOUs;

  @Value("${ldapLookupBaseDN}")
  private String ldapLookupBaseDN;

  @Value("${ldapEmbeded}")
  private boolean ldapEmbeded;

  @Value("${ldapSsl}")
  private boolean ldapSsl;

  @Value("${ldapLookupUser}")
  private String ldapLookupUser;

  @Value("${ldapLookupPassword}")
  private String ldapLookupPassword;

  @Autowired
  private userRepository userRepo;

  @Autowired
  private EmbeddedLdap embeddedLdap;

  private void tryStartEmbeddedLdap() {
    if (!ldapEmbeded) {
      return;
    }

    try {
      embeddedLdap.startup();
    } catch (LDAPException | IOException e) {
      logger.error("Failed to start embedded LDAP");
    }
  }

  @PostConstruct
  private void openConnection() {
    tryStartEmbeddedLdap();

    try {
      if (ldapSsl) {
        SSLUtil sslUtil = new SSLUtil(new TrustAllTrustManager());
        SSLSocketFactory sslSocketFactory = sslUtil.createSSLSocketFactory();
        ldapConnection = new LDAPConnection(sslSocketFactory);
      } else {
        ldapConnection = new LDAPConnection();
      }
      ldapConnection.connect(ldapUrl, ldapPort);
      logger.info("Successfully connected to LDAP");
    } catch (LDAPException | GeneralSecurityException e) {
      logger.error("Failed to connect to LDAP", e);
    }
  }

  @PreDestroy
  private void closeConnection() {
    if (ldapConnection != null) {
      ldapConnection.close();
    }
  }

  private void ensureConnection() {
    if (!ldapConnection.isConnected()) {
      openConnection();
    }
  }

  private void bindAsTechnicalUser() throws LDAPException {
    ldapConnection.bind(new SimpleBindRequest("cn=" + ldapLookupUser + "," + ldapLookupBaseDN, ldapLookupPassword));
  }

  @Retryable(include = OptimisticLockingFailureException.class, maxAttempts = 10)
  public User syncUser(User user) {
    List<User> lst = new ArrayList<>();
    lst.add(user);
    return syncUsers(lst, false).get(0);
  }

  private List<User> getNoDetailsUsers(Collection<User> users) {
    return users.stream().filter(u -> u.getLdapDetails() == null).collect(Collectors.toList());
  }

  private SearchResultEntry getEntryByMail(String mail) {
    try {
      for (String ou : ldapAuthBaseOUs.split(",")) {
        String dn = ldapAuthBaseDN.replace("{}", ou);
        SearchRequest ldapRequest = new SearchRequest(dn, SearchScope.SUB, "(mail=" + mail + ")");
        SearchResult ldapResult = ldapConnection.search(ldapRequest);

        if (ldapResult.getEntryCount() > 0) {
          return ldapResult.getSearchEntries().get(0);
        }
      }

      return null;
    } catch (LDAPException e) {
      return null;
    }
  }

  private SearchResultEntry getEntryById(String id) {
    try {
      for (String ou : ldapAuthBaseOUs.split(",")) {
        String dn = ldapAuthBaseDN.replace("{}", ou);
        logger.warn("tororlrlrororlro" + id);
        SearchRequest ldapRequest = new SearchRequest(dn, SearchScope.SUB, "(uid=" + id + ")");
        SearchResult ldapResult = ldapConnection.search(ldapRequest);

        if (ldapResult.getEntryCount() > 0) {
          return ldapResult.getSearchEntries().get(0);
        }
      }
      return null;
    } catch (LDAPException e) {
      return null;
    }
  }

  public User createUserByMail(String mail) {
      ensureConnection();
      try {
        bindAsTechnicalUser();
      } catch (LDAPException e) {
        logger.error("Failed to bind ldap as tech user", e);
      }

      SearchResultEntry ldapEntry = getEntryByMail(mail);
      if (ldapEntry == null) {
        logger.warn("Failed to sync user {} with LDAP: no result", mail);
        return null;
      }

      UserLdapDetails ldapDetails = new UserLdapDetails(ldapEntry);
      String dn = null;
      try {
        dn = ldapEntry.getParentDNString();
      } catch (LDAPException e) {
        e.printStackTrace();
      }
      String id = ldapEntry.getAttributeValue("uid");

      User newUser = new User(id);
      newUser.setLdapDN(dn);
      newUser.setLdapDetails(ldapDetails);
      return newUser;
  }

  public List<User> syncUsers(List<User> users, boolean forceUpdate) {
    ensureConnection();

    try {
      bindAsTechnicalUser();
    } catch (LDAPException e) {
      logger.error("Failed to sync users, LDAP error");
      return users;
    }

    List<User> updateables = forceUpdate ? users : getNoDetailsUsers(users);
    List<User> updated = new ArrayList<>();

    for (User user : updateables) {
      SearchRequest ldapRequest;
      SearchResultEntry ldapEntry;

      try {
        if (StringUtils.isEmpty(user.getLdapDN())) {
          ldapEntry = getEntryById(user.getId());
        } else {
          ldapRequest = new SearchRequest(user.getLdapDN(), SearchScope.SUB, "(uid=" + user.getId() + ")");
          List<SearchResultEntry> entries = ldapConnection.search(ldapRequest).getSearchEntries();
          ldapEntry = entries.size() < 1 ? null : entries.get(0);
        }

        if (ldapEntry == null) {
          logger.warn("Failed to sync user {}: Not found in LDAP, will remove", user.getId());
        } else {
          user.setLdapDetails(new UserLdapDetails(ldapEntry));
          updated.add(user);
        }
      } catch (LDAPException e) {
        logger.error("Failed to sync user {}: LDAP error", user.getId());
      }
    }

    userRepo.saveAll(updated);

    logger.info("Successfully synced {} users with LDAP", updated.size());
    return updated;
  }

}
