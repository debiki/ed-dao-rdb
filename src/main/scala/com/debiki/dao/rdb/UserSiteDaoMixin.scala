/**
 * Copyright (C) 2011-2015 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.dao.rdb

import com.debiki.core._
import com.debiki.core.EmailNotfPrefs.EmailNotfPrefs
import com.debiki.core.Prelude._
import com.debiki.core.User.{LowestNonGuestId, LowestAuthenticatedUserId}
import _root_.java.{util => ju, io => jio}
import java.{sql => js}
import scala.collection.{immutable, mutable}
import scala.collection.{mutable => mut}
import scala.collection.mutable.{ArrayBuffer, StringBuilder}
import Rdb._
import RdbUtil._


/** Creates and updates users and identities.
  */
trait UserSiteDaoMixin extends SiteDbDao with SiteTransaction {
  self: RdbSiteDao =>


  def insertInvite(invite: Invite) {
    val statement = """
      insert into dw2_invites(
        site_id, secret_key, email_address, created_by_id, created_at)
      values (?, ?, ?, ?, ?)
      """
    val values = List(
      siteId, invite.secretKey, invite.emailAddress,
      invite.createdById.asAnyRef, invite.createdAt.asTimestamp)
    try {
      runUpdate(statement, values)
    }
    catch {
      case ex: js.SQLException =>
        if (isUniqueConstrViolation(ex) && uniqueConstrViolatedIs("dw2_invites_email__u", ex))
          throw DbDao.DuplicateUserEmail

        throw ex
    }
  }


  def updateInvite(invite: Invite): Boolean = {
    val statement = """
      update dw2_invites set
        accepted_at = ?,
        user_id = ?,
        deleted_at = ?,
        deleted_by_id = ?,
        invalidated_at = ?
      where
        site_id = ? and
        secret_key = ?
      """
    val values = List(
      invite.acceptedAt.orNullTimestamp,
      invite.userId.orNullInt,
      invite.deletedAt.orNullTimestamp,
      invite.deletedById.orNullInt,
      invite.invalidatedAt.orNullTimestamp,
      siteId,
      invite.secretKey)
    runUpdateSingleRow(statement, values)
  }


  def loadInvite(secretKey: String): Option[Invite] = {
    val query = s"""
      select $InviteSelectListItems
      from dw2_invites
      where site_id = ? and secret_key = ?
      """
    val values = List(siteId, secretKey)
    runQuery(query, values, rs => {
      if (!rs.next())
        return None

      val invite = getInvite(rs)
      dieIf(rs.next(), "DwE7PK3W4")
      Some(invite)
    })
  }


  def loadInvites(createdById: UserId): immutable.Seq[Invite] = {
    val query = s"""
      select $InviteSelectListItems
      from dw2_invites
      where site_id = ? and created_by_id = ?
      order by created_at desc
      """
    val values = List(siteId, createdById.asAnyRef)
    val result = ArrayBuffer[Invite]()
    runQuery(query, values, rs => {
      while (rs.next()) {
        val invite = getInvite(rs)
        result.append(invite)
      }
    })
    result.toVector
  }


  def nextAuthenticatedUserId: UserId = {
    val query = s"""
      select max(user_id) max_id from dw1_users
      where site_id = ? and user_id >= $LowestAuthenticatedUserId
      """
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id")
      maxId + 1
    })
  }


  def nextIdentityId: IdentityId = {
    val query = s"""
      select max(id) max_id from dw1_identities
      where site_id = ?
      """
    runQuery(query, List(siteId), rs => {
      rs.next()
      val maxId = rs.getInt("max_id")
      (maxId + 1).toString
    })
  }


  def insertAuthenticatedUser(user: CompleteUser) {
    try {
      runUpdate("""
        insert into DW1_USERS(
            SITE_ID, USER_ID, DISPLAY_NAME, USERNAME, CREATED_AT,
            EMAIL, EMAIL_NOTFS, EMAIL_VERIFIED_AT, PASSWORD_HASH,
            IS_APPROVED, APPROVED_AT, APPROVED_BY_ID,
            COUNTRY, SUPERADMIN, IS_OWNER)
        values (
            ?, ?, ?, ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?, ?)
        """,
        List[AnyRef](siteId, user.id.asAnyRef, user.fullName.trimNullVarcharIfBlank,
          user.username, user.createdAt, user.emailAddress.trimNullVarcharIfBlank,
          _toFlag(user.emailNotfPrefs), o2ts(user.emailVerifiedAt),
          user.passwordHash.orNullVarchar,
          user.isApproved.orNullBoolean, user.approvedAt.orNullTimestamp,
          user.approvedById.orNullInt,
          user.country.trimNullVarcharIfBlank, tOrNull(user.isAdmin), tOrNull(user.isOwner)))
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex))
          throw ex

        if (uniqueConstrViolatedIs("DW1_USERS_SITE_EMAIL__U", ex))
          throw DbDao.DuplicateUserEmail

        if (uniqueConstrViolatedIs("DW1_USERS_SITE_USERNAME__U", ex))
          throw DbDao.DuplicateUsername

        throw ex
    }
  }


  def insertIdentity(identity: Identity) {
    identity match {
      case x: IdentityOpenId =>
        insertOpenIdIdentity(siteId, x)
      case x: OpenAuthIdentity =>
        insertOpenAuthIdentity(siteId, x)
      case x =>
        die("DwE8UYM0", s"Unknown identity type: ${classNameOf(x)}")
    }
  }


  private[rdb] def insertOpenIdIdentity(tenantId: SiteId, identity: IdentityOpenId) {
    val details = identity.openIdDetails
    runUpdate("""
            insert into DW1_IDENTITIES(
                SNO, SITE_ID, USER_ID, USER_ID_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                OID_REALM, OID_ENDPOINT, OID_VERSION,
                FIRST_NAME, EMAIL, COUNTRY)
            values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            List[AnyRef](identity.id, tenantId, identity.userId.asAnyRef, identity.userId.asAnyRef,
              details.oidClaimedId, e2d(details.oidOpLocalId), e2d(details.oidRealm),
              e2d(details.oidEndpoint), e2d(details.oidVersion),
              e2d(details.firstName), details.email.orNullVarchar, e2d(details.country)))
  }


  private[rdb] def _updateIdentity(identity: IdentityOpenId)
        (implicit connection: js.Connection) {
    val details = identity.openIdDetails
    db.update("""
      update DW1_IDENTITIES set
          USER_ID = ?, OID_CLAIMED_ID = ?,
          OID_OP_LOCAL_ID = ?, OID_REALM = ?,
          OID_ENDPOINT = ?, OID_VERSION = ?,
          FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
      where SNO = ? and SITE_ID = ?
      """,
      List[AnyRef](identity.userId.asAnyRef, details.oidClaimedId,
        e2d(details.oidOpLocalId), e2d(details.oidRealm),
        e2d(details.oidEndpoint), e2d(details.oidVersion),
        e2d(details.firstName), details.email.orNullVarchar, e2d(details.country),
        identity.id, siteId))
  }


  private def insertOpenAuthIdentity(
        otherSiteId: SiteId, identity: OpenAuthIdentity) {
    val sql = """
        insert into DW1_IDENTITIES(
            SNO, SITE_ID, USER_ID, USER_ID_ORIG,
            FIRST_NAME, LAST_NAME, FULL_NAME, EMAIL, AVATAR_URL,
            AUTH_METHOD, SECURESOCIAL_PROVIDER_ID, SECURESOCIAL_USER_ID)
        values (
            ?, ?, ?, ?,
            ?, ?, ?, ?, ?,
            ?, ?, ?)"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.id, otherSiteId, identity.userId.asAnyRef, identity.userId.asAnyRef,
      ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      method, ds.providerId, ds.providerKey)
    runUpdate(sql, values)
  }


  private[rdb] def updateOpenAuthIdentity(identity: OpenAuthIdentity)
        (implicit connection: js.Connection) {
    val sql = """
      update DW1_IDENTITIES set
        USER_ID = ?, AUTH_METHOD = ?,
        FIRST_NAME = ?, LAST_NAME = ?, FULL_NAME = ?, EMAIL = ?, AVATAR_URL = ?
      where SNO = ? and SITE_ID = ?"""
    val ds = identity.openAuthDetails
    val method = "OAuth" // should probably remove this column
    val values = List[AnyRef](
      identity.userId.asAnyRef, method, ds.firstName.orNullVarchar, ds.lastName.orNullVarchar,
      ds.fullName.orNullVarchar, ds.email.orNullVarchar, ds.avatarUrl.orNullVarchar,
      identity.id, siteId)
    db.update(sql, values)
  }


  def loadIdtyDetailsAndUser(userId: UserId): Option[(Identity, User)] = {
    db.withConnection(implicit connection => {
      _loadIdtyDetailsAndUser(forUserId = Some(userId)) match {
        case (None, None) => None
        case (Some(idty), Some(user)) => Some(idty, user)
        case (None, user) => assErr("DwE257IV2")
        case (idty, None) => assErr("DwE6BZl42")
      }
    })
  }


  /** Looks up detailed info on a single user. Only one forXxx param may be specified.
    */
  // COULD return Option(identity, user) instead of (Opt(id), Opt(user)).
  private[rdb] def _loadIdtyDetailsAndUser(
        forUserId: Option[UserId] = None,
        forOpenIdDetails: OpenIdDetails = null,
        forOpenAuthProfile: OpenAuthProviderIdKey = null)(implicit connection: js.Connection)
        : (Option[Identity], Option[User]) = {
    val anyOpenIdDetails = Option(forOpenIdDetails)
    val anyOpenAuthKey = Option(forOpenAuthProfile)

    val anyUserId = forUserId

    var sqlSelectFrom = """
        select
            """+ UserSelectListItemsNoGuests +""",
            i.ID i_id,
            i.OID_CLAIMED_ID,
            i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.SECURESOCIAL_USER_ID,
            i.SECURESOCIAL_PROVIDER_ID,
            i.AUTH_METHOD,
            i.FIRST_NAME i_first_name,
            i.LAST_NAME i_last_name,
            i.FULL_NAME i_full_name,
            i.EMAIL i_email,
            i.COUNTRY i_country,
            i.AVATAR_URL i_avatar_url
          from DW1_IDENTITIES i inner join DW1_USERS u
            on i.SITE_ID = u.SITE_ID
            and i.USER_ID = u.USER_ID
        """

    val (whereClause, bindVals) = (anyUserId, anyOpenIdDetails, anyOpenAuthKey) match {

      case (Some(userId: UserId), None, None) =>
        ("""where i.SITE_ID = ? and i.USER_ID = ?""",
           List(siteId, userId.asAnyRef))

      case (None, Some(openIdDetails: OpenIdDetails), None) =>
        // With Google OpenID, the identifier varies by realm. So use email
        // address instead. (With Google OpenID, the email address can be
        // trusted — this is not the case, however, in general.
        // See: http://blog.stackoverflow.com/2010/04/openid-one-year-later/
        // Quote:
        //  "If we have an email address from a verified OpenID email
        //   provider (that is, an OpenID from a large email service we trust,
        //   like Google or Yahoo), then it’s guaranteed to be a globally
        //   unique string.")
        val (claimedIdOrEmailCheck, idOrEmail) = {
          // SECURITY why can I trust the OpenID provider to specify
          // the correct endpoint? What if Mallory's provider replies
          // with Googles endpoint? I guess the Relying Party impl doesn't
          // allow this but anyway, I'd like to know for sure.
          ("i.OID_CLAIMED_ID = ?", openIdDetails.oidClaimedId)
        }
        ("""where i.SITE_ID = ?
            and """+ claimedIdOrEmailCheck +"""
          """, List(siteId, idOrEmail))

      case (None, None, Some(openAuthKey: OpenAuthProviderIdKey)) =>
        val whereClause =
          """where i.SITE_ID = ?
               and i.SECURESOCIAL_PROVIDER_ID = ?
               and i.SECURESOCIAL_USER_ID = ?"""
        val values = List(siteId, openAuthKey.providerId, openAuthKey.providerKey)
        (whereClause, values)

      case _ => assErr("DwE98239k2a2", "None, or more than one, lookup method specified")
    }

    db.query(sqlSelectFrom + whereClause, bindVals, rs => {
      if (!rs.next)
        return None -> None

      // Warning: Some dupl code in _loadIdtysAndUsers:
      // COULD break out construction of Identity to reusable
      // functions.

      val userInDb = _User(rs)

      val id = rs.getInt("i_id").toString
      val email = Option(rs.getString("i_email"))
      val anyClaimedOpenId = Option(rs.getString("OID_CLAIMED_ID"))
      val anyOpenAuthProviderId = Option(rs.getString("SECURESOCIAL_PROVIDER_ID"))

      val identityInDb = {
        if (anyClaimedOpenId.nonEmpty) {
          IdentityOpenId(
            id = id,
            userId = userInDb.id,
            // COULD use d2e here, or n2e if I store Null instead of '-'.
            OpenIdDetails(
              oidEndpoint = rs.getString("OID_ENDPOINT"),
              oidVersion = rs.getString("OID_VERSION"),
              oidRealm = rs.getString("OID_REALM"),
              oidClaimedId = anyClaimedOpenId.get,
              oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
              firstName = rs.getString("i_first_name"),
              email = email,
              country = rs.getString("i_country")))
        }
        else if (anyOpenAuthProviderId.nonEmpty) {
          OpenAuthIdentity(
            id = id,
            userId = userInDb.id,
            openAuthDetails = OpenAuthDetails(
              providerId = anyOpenAuthProviderId.get,
              providerKey = rs.getString("SECURESOCIAL_USER_ID"),
              firstName = Option(rs.getString("i_first_name")),
              lastName = Option(rs.getString("i_last_name")),
              fullName = Option(rs.getString("i_full_name")),
              email = email,
              avatarUrl = Option(rs.getString("i_avatar_url"))))
        }
        else {
          assErr("DwE77GJ2", s"Unknown identity in DW1_IDENTITIES, site: $siteId, id: $id")
        }
      }

      assErrIf(rs.next, "DwE53IK24", "More that one matching identity, when"+
         " looking up: "+ (anyUserId, anyOpenIdDetails, anyOpenAuthKey))

      Some(identityInDb) -> Some(userInDb)
    })
  }


  def loadUserByEmailOrUsername(emailOrUsername: String): Option[User] = {
    db.withConnection(connection => {
      loadUserByEmailOrUsernameImpl(emailOrUsername)(connection)
    })
  }


  def loadUserByEmailOrUsernameImpl(emailOrUsername: String)
        (implicit connection: js.Connection): Option[User] = {
    val sql = s"""
      select ${UserSelectListItemsNoGuests}
      from DW1_USERS u
      where u.SITE_ID = ? and u.USER_ID >= $LowestNonGuestId and (u.EMAIL = ? or u.USERNAME = ?)
      """
    val values = List(siteId, emailOrUsername, emailOrUsername)
    db.query(sql, values, rs => {
      if (!rs.next()) {
        None
      }
      else {
        val user = _User(rs)
        assErrIf(rs.next(), "DwE7FH46")
        Some(user)
      }
    })
  }


  def loadUsersOnPageAsMap2(pageId: PageId, siteId: Option[SiteId]): Map[UserId, User] = {
    require(siteId.isEmpty || siteId.get == this.siteId, "DwE8YQB4") // for now
    loadUsersOnPage2(pageId).groupBy(_.id).mapValues(_.head)
  }


  def loadUsersOnPage2(pageId: PageId): List[User] = {
    val sql = s"""
      select $UserSelectListItemsWithGuests
      from DW2_POSTS p left join DW1_USERS u
        on p.SITE_ID = u.SITE_ID and p.CREATED_BY_ID = u.USER_ID
        left join DW1_GUEST_PREFS e
        on u.SITE_ID = e.SITE_ID and u.EMAIL = e.EMAIL
        where p.SITE_ID = ?
          and p.PAGE_ID = ?
      """
    val values = List[AnyRef](siteId, pageId)
    var users: List[User] = Nil
    runQuery(sql, values, rs => {
      while (rs.next()) {
        val user = _User(rs)
        users ::= user
      }
    })
    users
  }


  def loadUser(userId: UserId): Option[User] =
    loadUsersAsList(userId::Nil).headOption


  def loadUsers(userIds: Seq[UserId]): immutable.Seq[User] =
    loadUsersAsList(userIds.toList)


  private[rdb] def loadUsersAsList(userIds: List[UserId]): List[User] = {
    val usersBySiteAndId =  // SHOULD specify quota consumers
      systemDaoSpi.loadUsers(Map(siteId -> userIds))
    usersBySiteAndId.values.toList
  }


  def loadUsersAsMap(userIds: Iterable[UserId]): Map[UserId, User] = {
    val usersBySiteAndId =  // SHOULD specify quota consumers
      systemDaoSpi.loadUsers(Map(siteId -> userIds.toList))
    usersBySiteAndId map { case (siteAndUserId, user) =>
      siteAndUserId._2 -> user
    }
  }


  def loadUsers(): immutable.Seq[User] = {
    val query = i"""
      select $UserSelectListItemsWithGuests
      from
        DW1_USERS u
      left join DW1_GUEST_PREFS e
        on u.EMAIL = e.EMAIL and u.SITE_ID = e.SITE_ID
      where
        u.SITE_ID = ?
      """
    val values = List(siteId)
    val result = ArrayBuffer[User]()
    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val user = _User(rs)
        result.append(user)
      }
    })
    result.to[Vector]
  }


  def loadCompleteUser(userId: UserId): Option[CompleteUser] = {
    require(User.isRoleId(userId), "DwE5FKE2")
    val sql = s"""
      select $CompleteUserSelectListItemsNoUserId
      from dw1_users
      where site_id = ? and user_id = ?
      """
    val values = List(siteId, userId.asAnyRef)
    runQuery(sql, values, rs => {
      if (!rs.next())
        return None

      val user = getCompleteUser(rs, userId = Some(userId))
      dieIf(rs.next(), "DwE80ZQ2")
      Some(user)
    })
  }


  def loadCompleteUsers(
        onlyApproved: Boolean = false,
        onlyPendingApproval: Boolean = false): immutable.Seq[CompleteUser] = {
    require(!onlyApproved || !onlyPendingApproval)

    val andIsApprovedEqWhatever =
      if (onlyPendingApproval) "and is_approved is null"
      else if (onlyApproved) "and is_approved = true"
      else ""

    val anyOrderBy =
      if (onlyPendingApproval) "order by created_at desc, user_id desc"
      else ""

    val query = s"""
      select $CompleteUserSelectListItemsWithUserId
      from dw1_users u
      where
        u.site_id = ? and
        u.user_id >= ${User.LowestAuthenticatedUserId}
        $andIsApprovedEqWhatever
        $anyOrderBy
      """

    val values = List(siteId)
    val result = ArrayBuffer[CompleteUser]()
    db.queryAtnms(query, values, rs => {
      while (rs.next) {
        val user = getCompleteUser(rs)
        result.append(user)
      }
    })
    result.to[Vector]
  }


  def updateCompleteUser(user: CompleteUser): Boolean = {
    val statement = """
      update dw1_users set
        updated_at = now_utc(),
        display_name = ?,
        username = ?,
        email = ?,
        email_verified_at = ?,
        email_for_every_new_post = ?,
        email_notfs = ?,
        password_hash = ?,
        country = ?,
        website = ?,
        is_approved = ?,
        approved_at = ?,
        approved_by_id = ?,
        suspended_at = ?,
        suspended_till = ?,
        suspended_by_id = ?,
        suspended_reason = ?,
        superadmin = ?,
        is_owner = ?
      where site_id = ? and user_id = ?
      """

    val values = List(
      user.fullName.trimNullVarcharIfBlank,
      user.username,
      user.emailAddress.trimNullVarcharIfBlank,
      user.emailVerifiedAt.orNullTimestamp,
      user.emailForEveryNewPost.asAnyRef,
      _toFlag(user.emailNotfPrefs),
      user.passwordHash.orNullVarchar,
      user.country.trimNullVarcharIfBlank,
      user.website.trimNullVarcharIfBlank,
      user.isApproved.orNullBoolean,
      user.approvedAt.orNullTimestamp,
      user.approvedById.orNullInt,
      user.suspendedAt.orNullTimestamp,
      user.suspendedTill.orNullTimestamp,
      user.suspendedById.orNullInt,
      user.suspendedReason.orNullVarchar,
      tOrNull(user.isAdmin),
      tOrNull(user.isOwner),
      siteId,
      user.id.asAnyRef)

    runUpdateSingleRow(statement, values)
  }


  def updateGuest(user: User): Boolean = {
    val statement = """
      update dw1_users set
        updated_at = now_utc(),
        display_name = ?,
        website = ?
      where site_id = ? and user_id = ?
      """
    val values = List(user.displayName, e2d(user.website), siteId, user.id.asAnyRef)
    try {
      runUpdateSingleRow(statement, values)
    }
    catch {
      case ex: js.SQLException =>
        if (isUniqueConstrViolation(ex) && uniqueConstrViolatedIs("dw1_user_guest__u", ex))
          throw DbDao.DuplicateGuest

        throw ex
    }
  }


  def listUsernames(pageId: PageId, prefix: String): Seq[NameAndUsername] = {
    if (prefix.isEmpty) {
      listUsernamesOnPage(pageId)
    }
    else {
      listUsernamesWithPrefix(prefix)
    }
  }


  private def listUsernamesOnPage(pageId: PageId): Seq[NameAndUsername] = {
    val sql = """
      select distinct u.DISPLAY_NAME, u.USERNAME
      from DW2_POSTS p inner join DW1_USERS u
         on p.SITE_ID = u.SITE_ID
        and p.CREATED_BY_ID = u.USER_ID
        and u.USERNAME is not null
      where p.SITE_ID = ? and p.PAGE_ID = ?"""
    val values = List(siteId, pageId)
    val result = mutable.ArrayBuffer[NameAndUsername]()
    db.queryAtnms(sql, values, rs => {
      while (rs.next()) {
        val fullName = Option(rs.getString("DISPLAY_NAME")) getOrElse ""
        val username = rs.getString("USERNAME")
        dieIf(username eq null, "DwE5BKG1")
        result += NameAndUsername(fullName = fullName, username = username)
      }
    })
    result.to[immutable.Seq]
  }


  private def listUsernamesWithPrefix(prefix: String): Seq[NameAndUsername] = {
    val sql = s"""
      select distinct DISPLAY_NAME, USERNAME
      from DW1_USERS
      where SITE_ID = ? and USERNAME like ? and USER_ID >= $LowestNonGuestId
      """
    val values = List(siteId, prefix + "%")
    val result = mutable.ArrayBuffer[NameAndUsername]()
    db.queryAtnms(sql, values, rs => {
      while (rs.next()) {
        result += NameAndUsername(
          fullName = Option(rs.getString("DISPLAY_NAME")) getOrElse "",
          username = rs.getString("USERNAME"))
      }
    })
    result.to[immutable.Seq]
  }


  def configIdtySimple(ctime: ju.Date, emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
    transactionCheckQuota { implicit connection =>
      // SECURITY should stop remembering old rows, or prune table if too many old rows. And after that,
      // start allowing over quota here (since will be an update only).

      // Mark the current row as 'O' (old) -- unless EMAIL_NOTFS is 'F'
      // (Forbidden Forever). Then leave it as is, and let the insert
      // below fail.
      // COULD check # rows updated? No, there might be no rows to update.
      db.update("""
          update DW1_GUEST_PREFS
          set VERSION = 'O' -- old
          where SITE_ID = ? and EMAIL = ? and VERSION = 'C'
            and EMAIL_NOTFS != 'F'
          """,
          List(siteId, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into DW1_GUEST_PREFS (
              SITE_ID, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, 'C', ?, ?)
          """,
          List(siteId, d2ts(ctime), emailAddr, _toFlag(emailNotfPrefs)))
    }
  }


  def loadRolePageSettings(roleId: RoleId, pageId: PageId): Option[RolePageSettings] = {
    val query = i"""
      select NOTF_LEVEL
      from DW1_ROLE_PAGE_SETTINGS
      where SITE_ID = ? and ROLE_ID = ? and PAGE_ID = ?"""
    val values = List(siteId, roleId.asAnyRef, pageId)
    db.queryAtnms(query, values, rs => {
      if (!rs.next)
        return None

      val notfLevel = flagToNotfLevel(rs.getString("NOTF_LEVEL"))
      return Some(RolePageSettings(notfLevel))
    })
  }


  def saveRolePageSettings(roleId: RoleId, pageId: PageId, settings: RolePageSettings)  {
    // Race condition below. Ignore it; a single user is unlikely to update itself
    // two times simultaneously.
    val updateDontInsert = loadRolePageSettings(roleId, pageId = pageId).isDefined
    val sql =
      if (updateDontInsert) """
        update DW1_ROLE_PAGE_SETTINGS
        set NOTF_LEVEL = ?
        where SITE_ID = ? and ROLE_ID = ? and PAGE_ID = ?"""
      else """
        insert into DW1_ROLE_PAGE_SETTINGS(
          NOTF_LEVEL, SITE_ID, ROLE_ID, PAGE_ID)
        values (?, ?, ?, ?)"""
    val values = List(notfLevelToFlag(settings.notfLevel), siteId, roleId.asAnyRef, pageId)
    transactionCheckQuota { implicit connection =>
      db.update(sql, values)
    }
  }


  def loadUserIdsWatchingPage(pageId: PageId): Seq[UserId] = {
    // Load people watching pageId only.
    // For now, ignore users watching any parent categories, consider `pageId` itself only.
    val sql = """
      select ROLE_ID
      from DW1_ROLE_PAGE_SETTINGS
      where SITE_ID = ? and PAGE_ID = ? and NOTF_LEVEL = 'W'"""
    var result = mutable.ArrayBuffer[UserId]()
    db.queryAtnms(sql, List(siteId, pageId), rs => {
      while (rs.next()) {
        result += rs.getInt("ROLE_ID")
      }
    })

    // Load people watching the whole site.
    val sqlWholeSite = """
      select USER_ID from DW1_USERS where SITE_ID = ? and EMAIL_FOR_EVERY_NEW_POST = true"""
    db.queryAtnms(sqlWholeSite, List(siteId), rs => {
      while (rs.next()) {
        result += rs.getInt("USER_ID")
      }
    })
    result.distinct.to[immutable.Seq]
  }


  private def notfLevelToFlag(notfLevel: PageNotfLevel) = notfLevel match {
    case PageNotfLevel.Watching => "W"
    case PageNotfLevel.Tracking => "T"
    case PageNotfLevel.Regular => "R"
    case PageNotfLevel.Muted => "M"
  }


  private def flagToNotfLevel(flag: String) = flag match {
    case "W" => PageNotfLevel.Watching
    case "T" => PageNotfLevel.Tracking
    case "R" => PageNotfLevel.Regular
    case "M" => PageNotfLevel.Muted
    case x => die("DwE7FK02", s"Bad notf level: `$x'")
  }

}



