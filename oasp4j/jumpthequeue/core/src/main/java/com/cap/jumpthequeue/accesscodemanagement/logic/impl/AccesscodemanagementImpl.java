package com.cap.jumpthequeue.accesscodemanagement.logic.impl;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import com.cap.jumpthequeue.accesscodemanagement.dataaccess.api.AccessCodeEntity;
import com.cap.jumpthequeue.accesscodemanagement.dataaccess.api.dao.AccessCodeDao;
import com.cap.jumpthequeue.accesscodemanagement.logic.api.Accesscodemanagement;
import com.cap.jumpthequeue.accesscodemanagement.logic.api.to.AccessCodeCto;
import com.cap.jumpthequeue.accesscodemanagement.logic.api.to.AccessCodeEto;
import com.cap.jumpthequeue.accesscodemanagement.logic.api.to.AccessCodeSearchCriteriaTo;
import com.cap.jumpthequeue.general.logic.base.AbstractComponentFacade;
import com.cap.jumpthequeue.queuemanagement.dataaccess.api.QueueEntity;
import com.cap.jumpthequeue.queuemanagement.dataaccess.api.dao.QueueDao;
import com.cap.jumpthequeue.queuemanagement.logic.api.to.QueueEto;
import com.cap.jumpthequeue.usermanagement.dataaccess.api.UserEntity;
import com.cap.jumpthequeue.usermanagement.dataaccess.api.dao.UserDao;
import com.cap.jumpthequeue.usermanagement.logic.api.to.UserSearchCriteriaTo;
import com.cap.jumpthequeue.visitorinfomanagement.dataaccess.api.VisitorInfoEntity;
import com.cap.jumpthequeue.visitorinfomanagement.dataaccess.api.dao.VisitorInfoDao;

import io.oasp.module.jpa.common.api.to.PaginatedListTo;

/**
 * Implementation of component interface of accesscodemanagement
 */
@Named
@Transactional
public class AccesscodemanagementImpl extends AbstractComponentFacade implements Accesscodemanagement {

  /** Logger instance. */
  private static final Logger LOG = LoggerFactory.getLogger(AccesscodemanagementImpl.class);

  /** @see #getAccessCodeDao() */
  @Inject
  private AccessCodeDao accessCodeDao;

  @Inject
  private UserDao userDao;

  @Inject
  private QueueDao queueDao;

  @Inject
  private VisitorInfoDao visitorDao;

  /**
   * The constructor.
   */
  public AccesscodemanagementImpl() {
    super();
  }

  @Override
  public AccessCodeCto findAccessCode(Long id) {

    LOG.debug("Get AccessCode with id {} from database.", id);
    AccessCodeEntity accessCode = getAccessCodeDao().findOne(id);

    accessCode.setEstimatedTime(getEstimatedTimeByQueueId(accessCode.getQueueId()));

    getAccessCodeDao().save(accessCode);

    AccessCodeCto cto = new AccessCodeCto();
    cto.setAccessCode(getBeanMapper().map(accessCode, AccessCodeEto.class));
    cto.setQueue(getBeanMapper().map(accessCode.getQueue(), QueueEto.class));
    return cto;
  }

  @Override
  public PaginatedListTo<AccessCodeEto> findAccessCodeEtos(AccessCodeSearchCriteriaTo criteria) {

    criteria.limitMaximumPageSize(MAXIMUM_HIT_LIMIT);
    PaginatedListTo<AccessCodeEntity> accesscodes = getAccessCodeDao().findAccessCodes(criteria);
    return mapPaginatedEntityList(accesscodes, AccessCodeEto.class);
  }

  @Override
  public boolean deleteAccessCode(Long accessCodeId) {

    AccessCodeEntity accessCode = getAccessCodeDao().find(accessCodeId);
    getAccessCodeDao().delete(accessCode);
    LOG.debug("The accessCode with id '{}' has been deleted.", accessCodeId);
    return true;
  }

  @Override
  public AccessCodeEto saveAccessCode(AccessCodeEto accessCode) {

    Objects.requireNonNull(accessCode, "accessCode");
    AccessCodeEntity accessCodeEntity = getBeanMapper().map(accessCode, AccessCodeEntity.class);

    // initialize, validate accessCodeEntity here if necessary
    AccessCodeEntity resultEntity = getAccessCodeDao().save(accessCodeEntity);
    LOG.debug("AccessCode with id '{}' has been created.", resultEntity.getId());

    return getBeanMapper().map(resultEntity, AccessCodeEto.class);
  }

  /**
   * Returns the field 'accessCodeDao'.
   *
   * @return the {@link AccessCodeDao} instance.
   */
  public AccessCodeDao getAccessCodeDao() {

    return this.accessCodeDao;
  }

  // Make AccessCode for normal user
  @Override
  public AccessCodeCto getVisitorAccessCode(String token) {

    Objects.requireNonNull(token, "token");

    // Get user
    UserSearchCriteriaTo usercriteria = new UserSearchCriteriaTo();
    usercriteria.setToken(token);
    PaginatedListTo<UserEntity> users = this.userDao.findUsers(usercriteria);

    if (users.getResult().size() == 0 || users.getResult().size() > 1) {
      throw new NotFoundException();
    }

    UserEntity user = users.getResult().get(0);

    // Validate User
    user.setValidated(true);
    getUserDao().save(user);

    // Get queue
    QueueEntity queue = getQueueDao().find(user.getQueueId());

    if (queue.equals(null)) {
      throw new InternalServerErrorException();
    }

    // Find last code for this queue
    AccessCodeSearchCriteriaTo accesscodecriteria = new AccessCodeSearchCriteriaTo();
    accesscodecriteria.setQueueId(queue.getId());
    accesscodecriteria.setPriority(false);
    PaginatedListTo<AccessCodeEntity> codes = this.accessCodeDao.findAccessCodes(accesscodecriteria);
    AccessCodeEntity accesscode;
    if (codes.getResult().size() == 0) {
      accesscode = new AccessCodeEntity();
    } else
      accesscode = codes.getResult().get(codes.getResult().size() - 1);

    // Create AccessCode
    AccessCodeEntity code = new AccessCodeEntity();
    code.setName(user.getName());
    code.setEmail(user.getEmail());
    code.setPhone(user.getPhone());
    code.setCreationTime(user.getCreationTime());
    code.setIdentificator(user.getIdentificator());
    code.setQueue(queue);
    code.setPriority(false);
    code.setCode((codes.getResult().size() == 0) ? 1 : accesscode.getCode() < 999 ? accesscode.getCode() + 1 : 1);

    // Calculate estimated time
    code.setEstimatedTime(getEstimatedTimeByQueueId(queue.getId()));

    // Save AccessCode
    getAccessCodeDao().save(code);

    // Generate AccessCode CTO
    AccessCodeCto cto = new AccessCodeCto();
    cto.setAccessCode(getBeanMapper().map(code, AccessCodeEto.class));
    cto.setQueue(getBeanMapper().map(queue, QueueEto.class));

    // Transfert user info to VisitorInfpo Table if consented
    if (user.getConsent()) {
      saveVisitorInfo(user, queue);
    }

    // Return AccessCodeCto
    return cto;
  }

  /**
   * @param id
   * @param creationTime
   * @return
   */
  private Timestamp getEstimatedTimeByQueueId(Long queueId) {

    // Get current time -- Use Date or Calendar instead of Timestamps?
    Timestamp currentTimestamp = Timestamp.from(Instant.now());

    // Get last ten attention time
    AccessCodeSearchCriteriaTo accessCodeCriteria = new AccessCodeSearchCriteriaTo();
    accessCodeCriteria.setQueueId(queueId);
    PaginatedListTo<AccessCodeEntity> codes =
        this.accessCodeDao.findLastTenAttendedAccessCodesByQueue(accessCodeCriteria);

    // Get number of attended codes
    Long attendedCount = getAccessCodeDao().getAttendedCodesCount(accessCodeCriteria);

    // Get number of non attended codes
    accessCodeCriteria.setCreationTime(currentTimestamp);
    Long notAttendedCount = getAccessCodeDao().getNotAttendedCodesCountByTimestamp(accessCodeCriteria);

    // Calculate estimated time
    // Get queue and its minimum waiting time
    QueueEntity queue = getQueueDao().find(queueId);
    long minWait = queue.getMinWait();

    // Calculate 10 attention time
    long lastTenAttentionTime = 0;
    for (int i = 0; i < codes.getResult().size(); i++) {
      lastTenAttentionTime = lastTenAttentionTime + ((codes.getResult().get(i).getEndTime().getTime() / 1000)
          - (codes.getResult().get(i).getStartTime().getTime() / 1000));
    }
    // make calcul
    long estimatedTime = 0;

    // lastTenAttentionTime = lastTenAttentionTime / 1000;
    if (attendedCount != 0) {
      estimatedTime = ((currentTimestamp.getTime()) + (lastTenAttentionTime / attendedCount) * notAttendedCount);
    }
    LOG.info(String.valueOf(estimatedTime));

    estimatedTime = estimatedTime / 1000;

    if (estimatedTime < (currentTimestamp.getTime() / 1000 + (60 * minWait))) {
      estimatedTime = currentTimestamp.getTime() / 1000 + (60 * minWait);
    }
    // Return only with seconds

    return new Timestamp(estimatedTime);
  }

  @Override
  public AccessCodeCto getVIPAccessCode(String token) {

    Objects.requireNonNull(token, "token");

    // Get user
    UserSearchCriteriaTo usercriteria = new UserSearchCriteriaTo();
    usercriteria.setToken(token);
    PaginatedListTo<UserEntity> users = this.userDao.findUsers(usercriteria);

    if (users.getResult().size() == 0 || users.getResult().size() > 1) {
      throw new NotFoundException();
    }

    UserEntity user = users.getResult().get(0);

    // Validate User
    user.setValidated(true);
    getUserDao().save(user);

    // Get queue
    QueueEntity queue = getQueueDao().find(user.getQueueId());

    if (queue.equals(null)) {
      throw new InternalServerErrorException();
    }

    // Find last code for this queue
    AccessCodeSearchCriteriaTo accesscodecriteria = new AccessCodeSearchCriteriaTo();
    accesscodecriteria.setQueueId(queue.getId());
    accesscodecriteria.setPriority(true);
    PaginatedListTo<AccessCodeEntity> codes = this.accessCodeDao.findAccessCodes(accesscodecriteria);

    if (codes.getResult().size() == 0) {
      throw new NotFoundException();
    }

    AccessCodeEntity accesscode = codes.getResult().get(codes.getResult().size() - 1);

    // Create AccessCode
    AccessCodeEntity code = new AccessCodeEntity();
    code.setName(user.getName());
    code.setEmail(user.getEmail());
    code.setPhone(user.getPhone());
    code.setCreationTime(user.getCreationTime());
    code.setIdentificator(user.getIdentificator());
    code.setQueue(queue);
    code.setPriority(true);
    code.setCode(accesscode.getCode() < 999 ? accesscode.getCode() + 1 : 1);

    // Calculate estimated time
    code.setEstimatedTime(getEstimatedTimeByQueueId(queue.getId()));

    // Save AccessCode
    getAccessCodeDao().save(code);

    // Generate AccessCode CTO
    AccessCodeCto cto = new AccessCodeCto();
    cto.setAccessCode(getBeanMapper().map(code, AccessCodeEto.class));
    cto.setQueue(getBeanMapper().map(queue, QueueEto.class));

    // Transfert user info to VisitorInfpo Table if consented
    if (user.getConsent()) {
      saveVisitorInfo(user, queue);
    }

    // Return AccessCodeCto
    return cto;
  }

  @Override
  public AccessCodeCto getAttendingAccessCode(long queueId) {

    // Find Actual attending AccessCode
    AccessCodeSearchCriteriaTo accescodecriteria = new AccessCodeSearchCriteriaTo();
    accescodecriteria.setQueueId(queueId);
    PaginatedListTo<AccessCodeEntity> codes = getAccessCodeDao().findAttendingAccessCode(accescodecriteria);

    AccessCodeEntity atendingAccesCode = codes.getResult().get(codes.getResult().size() - 1);

    // return Cto with result
    AccessCodeCto attendingCodeCto = new AccessCodeCto();
    attendingCodeCto.setAccessCode(getBeanMapper().map(atendingAccesCode, AccessCodeEto.class));
    attendingCodeCto.setQueue(getBeanMapper().map(atendingAccesCode.getQueue(), QueueEto.class));

    return attendingCodeCto;
  }

  /**
   * Return AccessCode for one User
   *
   * @param UserSearchCriteriaTo
   *
   * @return AccessCodeCto
   */
  @Override
  public AccessCodeCto findExistingAccessCodesByPost(UserSearchCriteriaTo searchCriteriaTo) {

    // Find Users - Isolating qeueuId for AccessCode Only
    UserSearchCriteriaTo userSearchCriteriaTo = new UserSearchCriteriaTo();
    userSearchCriteriaTo.setIdentificator(searchCriteriaTo.getIdentificator());

    if (StringUtils.isEmpty(searchCriteriaTo.getEmail()) && StringUtils.isEmpty(searchCriteriaTo.getPhone())) {
      throw new BadRequestException();
    }

    if (searchCriteriaTo.getEmail() == null) {
      userSearchCriteriaTo.setPhone(searchCriteriaTo.getPhone());
    } else {
      userSearchCriteriaTo.setEmail(searchCriteriaTo.getEmail());
    }

    PaginatedListTo<UserEntity> users = getUserDao().findUsers(userSearchCriteriaTo);

    if (users.getResult().size() == 0) {
      throw new InternalServerErrorException();
    }
    UserEntity foundUser = users.getResult().get(users.getResult().size() - 1);

    // Error if not validated yet
    if (!foundUser.getValidated()) {
      throw new InternalServerErrorException();
    }

    // Find if User already have an AccessCode
    AccessCodeSearchCriteriaTo codescriteria = new AccessCodeSearchCriteriaTo();
    codescriteria.setIdentificator(foundUser.getIdentificator());
    codescriteria.setEmail(searchCriteriaTo.getEmail());
    PaginatedListTo<AccessCodeEntity> codes = getAccessCodeDao().findAccessCodes(codescriteria);

    AccessCodeEntity code;
    if (codes.getResult().size() == 0) {
      code = getnewAccessCode(foundUser, searchCriteriaTo.getQueueId());
    } else
      code = codes.getResult().get(codes.getResult().size() - 1);

    // Recalculate EstimatedTime
    code.setEstimatedTime(getEstimatedTimeByQueueId(code.getQueueId()));

    AccessCodeCto foundCode = new AccessCodeCto();

    // Return AccessCode
    foundCode.setAccessCode(getBeanMapper().map(code, AccessCodeEto.class));
    foundCode.setQueue(getBeanMapper().map(code.getQueue(), QueueEto.class));
    return foundCode;
  }

  /**
   * @param foundUser
   * @param queueId
   * @return
   */
  private AccessCodeEntity getnewAccessCode(UserEntity foundUser, Long queueId) {

    // Get queue
    QueueEntity queue = getQueueDao().find(queueId);

    if (queue.equals(null)) {
      throw new InternalServerErrorException();
    }

    // Find last code for this queue
    AccessCodeSearchCriteriaTo accesscodecriteria = new AccessCodeSearchCriteriaTo();
    accesscodecriteria.setQueueId(queue.getId());
    PaginatedListTo<AccessCodeEntity> codes = this.accessCodeDao.findAccessCodes(accesscodecriteria);

    if (codes.getResult().size() == 0) {
      throw new NotFoundException();
    }

    AccessCodeEntity accesscode = codes.getResult().get(codes.getResult().size() - 1);
    // Create AccessCode
    AccessCodeEntity code = new AccessCodeEntity();
    code.setName(foundUser.getName());
    code.setEmail(foundUser.getEmail());
    code.setPhone(foundUser.getPhone());
    code.setCreationTime(Timestamp.from(Instant.now()));
    code.setIdentificator(foundUser.getIdentificator());
    code.setQueue(queue);
    code.setPriority(false);
    code.setCode(accesscode.getCode() < 999 ? accesscode.getCode() + 1 : 1);

    // Calculate estimated time
    code.setEstimatedTime(getEstimatedTimeByQueueId(queue.getId()));

    // Save AccessCode
    getAccessCodeDao().save(code);

    // Return AccessCode ETO
    return code;
  }

  // Save Visitor Info
  private void saveVisitorInfo(UserEntity user, QueueEntity queue) {

    VisitorInfoEntity visitorInfoEntity = new VisitorInfoEntity();
    // Set info
    visitorInfoEntity.setName(user.getName());
    visitorInfoEntity.setPhone(user.getPhone());
    visitorInfoEntity.setEmail(user.getEmail());
    visitorInfoEntity.setQueueId(queue.getId());
    // Save VisitorInfo
    getVisitorInfoDao().save(visitorInfoEntity);
  }

  /**
   * @return
   */
  private UserDao getUserDao() {

    return this.userDao;
  }

  /**
   * @return
   */
  private VisitorInfoDao getVisitorInfoDao() {

    return this.visitorDao;
  }

  /**
   * @return
   */
  private QueueDao getQueueDao() {

    return this.queueDao;
  }

  // Generate number's AccessCodes for mock/estimated time calculus for testing sake

  @Override
  public AccessCodeEto makeAccessCode(long number) {

    // Get current time -- Use Date or Calendar instead of Timestamps?
    Timestamp currentTimestamp = Timestamp.from(Instant.now());

    // Make priority accessCodes
    AccessCodeEto eto = new AccessCodeEto();
    eto.setPriority(true);
    eto.setQueueId((long) 1233444);
    for (int i = 1; i < (number / 2); i++) {
      eto.setName("Peter" + i);
      eto.setEmail("petermail" + i + "@mail.com");
      eto.setCode(i);
      eto.setPhone("600 800 70" + i);
      eto.setIdentificator("A1B" + i);
      eto.setCreationTime(currentTimestamp);
      if (i < 2) {
        eto.setStartTime(new Timestamp(currentTimestamp.getTime() + (1000 * 60 * i)));
        eto.setEndTime(new Timestamp(currentTimestamp.getTime() + (1000 * 60 * i * 2)));
      } else {
        eto.setStartTime(null);
        eto.setEndTime(null);
      }
      eto.setEstimatedTime(new Timestamp((currentTimestamp.getTime() + (1000 * 60 * i)) / 1000));
      saveAccessCode(eto);
    }

    // Make non-priority accessCodes
    eto.setPriority(false);
    for (int i = 0; i < (number / 2); i++) {
      eto.setName("Marie" + i);
      eto.setEmail("mariemail" + i + "@mail.com");
      eto.setCode(i);
      eto.setPhone("600 900 70" + i);
      eto.setIdentificator("A2B" + i);
      eto.setCreationTime(currentTimestamp);
      if (i % 2 == 0) {
        eto.setStartTime(new Timestamp(currentTimestamp.getTime() + (1000 * 60 * i)));
        eto.setEndTime(new Timestamp(currentTimestamp.getTime() + (1000 * 60 * i * 2)));
      } else {
        eto.setStartTime(null);
        eto.setEndTime(null);
      }
      eto.setEstimatedTime(new Timestamp((currentTimestamp.getTime() + (1000 * 60 * i)) / 1000));
      saveAccessCode(eto);
    }
    return eto;
  }

}