/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.Dao._
import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.net.liftweb.common.{Loggable, Box, Empty, Full, Failure}
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.collection.{mutable => mut}


class RelDbDaoSpi(val db: RelDb) extends DaoSpi with Loggable {
  // COULD serialize access, per page?

  import RelDb._

  def close() { db.close() }

  def checkRepoVersion() = Full("0.0.2")

  def secretSalt(): String = "9KsAyFqw_"

  def createPage(where: PagePath, debatePerhapsGuid: Debate): Box[Debate] = {
    var debate = if (debatePerhapsGuid.guid != "?") {
      unimplemented
      // Could use debatePerhapsGuid, instead of generatinig a new guid,
      // but i have to test that this works. But should where.guid
      // be Some or None? Would Some be the guid to reuse, or would Some
      // indicate that the page already exists, an error!?
    } else {
      debatePerhapsGuid.copy(guid = nextRandomString)  // TODO use the same
                                              // method in all DAO modules!
    }
    db.transaction { implicit connection =>
      _createPage(where, debate)
      val postsWithIds =
        _insert(where.tenantId, debate.guid, debate.posts).open_!
      Full(debate.copy(posts = postsWithIds))
    }
  }

  override def saveLogin(tenantId: String, loginReq: LoginRequest
                            ): LoginGrant = {

    // Assigns an id to `loginNoId', saves it and returns it (with id).
    def _saveLogin(loginNoId: Login, identityWithId: Identity)
                  (implicit connection: js.Connection): Login = {
      // Create a new _LOGINS row, pointing to identityWithId.
      require(loginNoId.id startsWith "?")
      require(loginNoId.identityId startsWith "?")
      val loginSno = db.nextSeqNo("DW1_LOGINS_SNO")
      val login = loginNoId.copy(id = loginSno.toString,
                                identityId = identityWithId.id)
      val identityType = identityWithId match {
        case _: IdentitySimple => "Simple"
        case _: IdentityOpenId => "OpenID"
        case _ => assErr("[debiki_error_03k2r21K5]")
      }
      db.update("""
          insert into DW1_LOGINS(
              SNO, TENANT, PREV_LOGIN, ID_TYPE, ID_SNO,
              LOGIN_IP, LOGIN_TIME)
          values (?, ?, ?,
              '"""+
              // Don't bind identityType, that'd only make it harder for
              // the optimizer.
              identityType +"', ?, ?, ?)",
          List(loginSno.asInstanceOf[AnyRef], tenantId,
              e2n(login.prevLoginId),  // UNTESTED unless empty
              login.identityId.toLong.asInstanceOf[AnyRef],
              login.ip, login.date))
      login
    }

    // Special case for IdentitySimple, because they map to no User.
    // Instead we create a "fake" User (that is not present in DW1_USERS)
    // and assign it id = -IdentitySimple.id (i.e. a leading dash)
    // and return it.
    //    I don't want to save IdentitySimple people in DW1_USERS, because
    // I think that'd make possible future security *bugs*, when privileges
    // are granted to IdentitySimple users, which don't even need to
    // specify any password to "login"!
    loginReq.identity match {
      case s: IdentitySimple =>
        val simpleLogin = db.transaction { implicit connection =>
          // Find or create _SIMPLE row.
          var idtyId = ""
          var emailNotfsStr = ""
          for (i <- 1 to 2 if idtyId.isEmpty) {
            db.query("""
                select i.SNO, e.EMAIL_NOTFS from DW1_IDS_SIMPLE i
                  left join DW1_IDS_SIMPLE_EMAIL e
                  on i.EMAIL = e.EMAIL and e.VERSION = 'C'
                where i.NAME = ? and i.EMAIL = ? and i.LOCATION = ? and
                      i.WEBSITE = ?
                """,
                List(e2d(s.name), e2d(s.email), e2d(s.location),
                    e2d(s.website)),
                rs => {
              if (rs.next) {
                idtyId = rs.getString("SNO")
                emailNotfsStr = rs.getString("EMAIL_NOTFS")
              }
              Empty // dummy
            })
            if (idtyId isEmpty) {
              // Create simple user info.
              // There is a unique constraint on NAME, EMAIL, LOCATION,
              // WEBSITE, so this insert might fail (if another thread does
              // the insert, just before). Should it fail, the above `select'
              // is run again and finds the row inserted by the other thread.
              // Could avoid logging any error though!
              db.update("""
                  insert into DW1_IDS_SIMPLE(
                      SNO, NAME, EMAIL, LOCATION, WEBSITE)
                  values (nextval('DW1_IDS_SNO'), ?, ?, ?, ?)""",
                  List(s.name, e2d(s.email), e2d(s.location), e2d(s.website)))
                  // COULD fix: returning SNO into ?""", saves 1 roundtrip.
              // Loop one more lap to read SNO.
            }
          }
          assErrIf(idtyId.isEmpty, "[debiki_error_93kRhk20")
          val notfPrefs: EmailNotfPrefs = _fromFlagEmailNotfs(emailNotfsStr)
          val user = _dummyUserFor(identity = s, emailNotfPrefs = notfPrefs,
                                  id = _dummyUserIdFor(idtyId))
          val identityWithId = s.copy(id = idtyId, userId = user.id)
          val loginWithId = _saveLogin(loginReq.login, identityWithId)
          Full(LoginGrant(loginWithId, identityWithId, user))
        }.open_!
        return simpleLogin
      case _ => ()
    }

    // Load the User, if any, and the OpenID/Twitter/Facebook identity id:
    // Construct a query that joins the relevant DW1_IDS_<id-type> table
    // with DW1_USERS.
    // COULD move this user loading code to _loadUsers. This code
    // loads a user and also its *identity* though.
    var sqlSelectList = """
        select
            u.SNO USER_SNO,
            u.DISPLAY_NAME,
            u.EMAIL,
            u.EMAIL_NOTFS,
            u.COUNTRY,
            u.WEBSITE,
            u.SUPERADMIN,
            i.SNO IDENTITY_SNO,
            i.USR,
            i.USR_ORIG,
            """

    // SQL for selecting User and Identity. Depends on the Identity type.
    val (sqlFromWhere, bindVals) = loginReq.identity match {
      case oid: IdentityOpenId =>
        ("""i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.FIRST_NAME,
            i.EMAIL,
            i.COUNTRY
          from DW1_IDS_OPENID i, DW1_USERS u
            -- in the future: could join with DW1_IDS_OPENID_ATRS,
            -- which would store the FIRST_NAME, EMAIL, COUNTRY
            -- that the OpenID provider sent, originally (but which the
            -- user might have since changed).
          where i.TENANT = ?
            and i.OID_CLAIMED_ID = ?
            and i.TENANT = u.TENANT -- (not needed, u.SNO is unique)
            and i.USR = u.SNO
            """,
          List(tenantId, oid.oidClaimedId)
        )
      // case fid: IdentityTwitter => (SQL for Twitter identity table)
      // case fid: IdentityFacebook => (...)
      case _: IdentitySimple => assErr("[debiki_error_98239k2a2]")
      case IdentityUnknown => assErr("[debiki_error_92k2rI06]")
    }

    db.transaction { implicit connection =>
      // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
          db.query(sqlSelectList + sqlFromWhere, bindVals, rs => {
        if (rs.next) {
          val userInDb = User(
              id = rs.getLong("USER_SNO").toString,
              displayName = n2e(rs.getString("DISPLAY_NAME")),
              email = n2e(rs.getString("EMAIL")),
              emailNotfPrefs = _fromFlagEmailNotfs(
                                  rs.getString("EMAIL_NOTFS")),
              country = n2e(rs.getString("COUNTRY")),
              website = n2e(rs.getString("WEBSITE")),
              isSuperAdmin = rs.getString("SUPERADMIN") == "T")
          val identityInDb = loginReq.identity match {
            case iod: IdentityOpenId =>
              IdentityOpenId(
                id = rs.getLong("IDENTITY_SNO").toString,
                userId = userInDb.id,
                // COULD use d2e here, or n2e if I store Null instead of '-'.
                oidEndpoint = rs.getString("OID_ENDPOINT"),
                oidVersion = rs.getString("OID_VERSION"),
                oidRealm = rs.getString("OID_REALM"),
                oidClaimedId = iod.oidClaimedId,
                oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
                firstName = rs.getString("FIRST_NAME"),
                email = rs.getString("EMAIL"),
                country = rs.getString("COUNTRY"))
            // case _: IdentityTwitter =>
            // case _: IdentityFacebook =>
            case sid: IdentitySimple => assErr("[debiki_error_8451kx350]")
            case IdentityUnknown => assErr("[debiki_error_091563wkr21]")
          }
          Full(Some(identityInDb) -> Some(userInDb))
        } else {
          Full(None -> None)
        }
      }).open_!

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
          // Leave the name/email/etc fields blank --  the name/email from
          // the relevant identity is used instead. However, if the user
          // manually fills in (not implemented) her user data,
          // then those values take precedence (over the one from the
          // identity provider).
          val userSno = db.nextSeqNo("DW1_USERS_SNO")
          val u =  User(id = userSno.toString, displayName = "", email = "",
                        emailNotfPrefs = EmailNotfPrefs.DontReceive,
                        country = "", website = "", isSuperAdmin = false)
          db.update("""
              insert into DW1_USERS(
                  TENANT, SNO, DISPLAY_NAME, EMAIL, COUNTRY)
              values (?, ?, ?, ?, ?)""",
              List(tenantId, userSno.asInstanceOf[AnyRef],
                  e2n(u.displayName), e2n(u.email), e2n(u.country)))
          u
      }

      // Create or update the OpenID/Twitter/etc identity.
      //
      // (It's absent, if this is the first time the user logs in.
      // It needs to be updated, if the user has changed e.g. her
      // OpenID name or email. Or Facebook name or email.)
      //
      // (Concerning simultaneous inserts/updates by different threads or
      // server nodes: This insert might result in a unique key violation
      // error. Simply let the error propagate and the login fail.
      // This login was supposedly initiated by a human, and there is
      // no point in allowing exactly simultaneous logins by one
      // single human.)

      val newIdentitySno: Option[Long] =
          if (identityInDb.isDefined) None
          else Some(db.nextSeqNo("DW1_IDS_SNO"))

      val identity = (identityInDb, loginReq.identity) match {
        case (None, newNoId: IdentityOpenId) =>
          // Assign id and create.
          val nev = newNoId.copy(id = newIdentitySno.get.toString,
                                 userId = user.id)
          db.update("""
              insert into DW1_IDS_OPENID(
                  SNO, TENANT, USR, USR_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                  OID_REALM, OID_ENDPOINT, OID_VERSION,
                  FIRST_NAME, EMAIL, COUNTRY)
              values (
                  ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
              List(newIdentitySno.get.asInstanceOf[AnyRef],
                  tenantId, nev.userId, nev.userId,
                  nev.oidClaimedId, e2d(nev.oidOpLocalId), e2d(nev.oidRealm),
                  e2d(nev.oidEndpoint), e2d(nev.oidVersion),
                  e2d(nev.firstName), e2d(nev.email), e2d(nev.country)))
          nev
        case (Some(old: IdentityOpenId), newNoId: IdentityOpenId) =>
          val nev = newNoId.copy(id = old.id, userId = user.id)
          if (nev != old) {
            // COULD aovid overwriting email with nothing?
            db.update("""
                update DW1_IDS_OPENID set
                    USR = ?, OID_OP_LOCAL_ID = ?, OID_REALM = ?,
                    OID_ENDPOINT = ?, OID_VERSION = ?,
                    FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
                where SNO = ? and TENANT = ?
                """,
                List(nev.userId,  e2d(nev.oidOpLocalId), e2d(nev.oidRealm),
                  e2d(nev.oidEndpoint), e2d(nev.oidVersion),
                  e2d(nev.firstName), e2d(nev.email), e2d(nev.country),
                  nev.id, tenantId))//.toLong.asInstanceOf[AnyRef], tenantId))
          }
          nev
        // case (..., IdentityTwitter) => ...
        // case (..., IdentityFacebook) => ...
        case (_, _: IdentitySimple) => assErr("[debiki_error_83209qk12kt0]")
        case (_, IdentityUnknown) => assErr("[debiki_error_32ks30016")
      }

      val login = _saveLogin(loginReq.login, identity)

      Full(LoginGrant(login, identity, user))
    }.open_!  // pointless box
  }

  override def saveLogout(loginId: String, logoutIp: String) {
    db.transaction { implicit connection =>
      db.update("""
          update DW1_LOGINS set LOGOUT_IP = ?, LOGOUT_TIME = ?
          where SNO = ?""", List(logoutIp, new ju.Date, loginId)) match {
        case Full(1) => Empty  // ok
        case Full(x) => assErr("Updated "+ x +" rows [debiki_error_03k21rx1]")
        case badBox => unimplemented // remove boxes
      }
    }
  }

  private def _loadRequesterInfo(reqInfo: RequestInfo
                                    ): RequesterInfo = reqInfo.loginId match {
    case None =>
      RequesterInfo(ip = reqInfo.ip, login = None,
            identity = None, user = None, memships = Nil)
    case Some(id) =>
      loadUser(withLoginId = id, tenantId = reqInfo.tenantId) match {
        case None =>
          error("Found no identity for identity "+ safed(id) +
              ", tenant "+ reqInfo.tenantId +" [debiki_error_0921kxa13]")
        case Some((i: Identity, u: User)) =>
          val info = RequesterInfo(ip = reqInfo.ip, login = None, // not loaded
                identity = Some(i), user = Some(u), memships = Nil)
          val memships_ = _loadMemships(reqInfo.tenantId, info)
          info.copy(memships = memships_)
      }
  }

  def loadUser(withLoginId: String, tenantId: String
                  ): Option[(Identity, User)] = {
    def loginInfo = "login id "+ safed(withLoginId) +
          ", tenant "+ safed(tenantId)

    _loadUsers(withLoginId = withLoginId, tenantId = tenantId) match {
      case (List(i: Identity), List(u: User)) => Some(i, u)
      case (List(i: Identity), Nil) => assErr(
        "Found no user for "+ loginInfo +
            ", with identity "+ safed(i.id) +" [debiki_error_6349krq20]")
      case (Nil, Nil) =>
        // The webapp should never try to load non existing identities?
        // (The login id was once fetched from the database.
        // It is sent to the client in a signed cookie so it cannot be
        // tampered with.) Identities cannot be deleted!
        error("Found no identity for "+ loginInfo +" [debiki_error_0921kxa13]")
      case (is, us) =>
        // There should be exactly one identity per login, and at most
        // one user per identity.
        assErr("Found "+ is.length +" identities and "+ us.length +
            " users for "+ loginInfo +" [debiki_error_42RxkW1]")
    }
  }

  private def _loadUsers(onPageWithSno: String = null,
                         withLoginId: String = null,
                         tenantId: String
                            ): Pair[List[Identity], List[User]] = {
    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.

    val (selectLoginIds, args) = (onPageWithSno, withLoginId) match {
      // (Need to specify tenant id here, and when selecting from DW1_USERS,
      // because there's no foreign key from DW1_LOGINS to DW1_IDS_<type>.)
      case (null, loginId) => ("""
          select ID_SNO, ID_TYPE
              from DW1_LOGINS
              where SNO = ? and TENANT = ?
          """, List(loginId, // why not needed: .toLong.asInstanceOf[AnyRef]?
                     tenantId))
      case (pageSno, null) => ("""
          select distinct l.ID_SNO, l.ID_TYPE
              from DW1_PAGE_ACTIONS a, DW1_LOGINS l
              where a.PAGE = ? and a.LOGIN = l.SNO and l.TENANT = ?
          """, List(pageSno.asInstanceOf[AnyRef], tenantId))
      case (a, b) =>
          illegalArg("onPageWithSno: "+ safed(a) +", withLoginId: "+ safed(b))
    }

    // Load identities and users. Details: First find identities of all types
    // by joining logins with each identity table, and then taking the union
    // of all these joins. Use generic column names (since each identity
    // table has identity provider specific column names).
    // Then join all the identities found with DW1_USERS.
    // Note: There are identities with no matching users (IdentitySimple),
    // so do a left outer join.
    // Note: There might be > 1 identity per user (if a user has merged
    // e.g. her Twitter and Facebook identities to one single user account).
    // So each user might be returned > 1 times, i.e. once per identity.
    // This wastes some bandwidth, but I guess it's better than doing a
    // separate query to fetch all relevant users exactly once -- that
    // additional roundtrip to the database would probably be more expensive;
    // I guess fairly few users will merge their identities.
    db.queryAtnms("""
        with logins as ("""+ selectLoginIds +"""),
        identities as (
            -- Simple identities
            select ID_TYPE, s.SNO I_ID, '' I_USR,
                   s.NAME I_NAME, s.EMAIL I_EMAIL,
                   e.EMAIL_NOTFS I_EMAIL_NOTFS,
                   s.LOCATION I_WHERE, s.WEBSITE I_WEBSITE
            from logins, DW1_IDS_SIMPLE s
              left join DW1_IDS_SIMPLE_EMAIL e
              on s.EMAIL = e.EMAIL and e.VERSION = 'C'
            where s.SNO = logins.ID_SNO and logins.ID_TYPE = 'Simple'
            union
            -- OpenID
            select ID_TYPE, oi.SNO I_ID, oi.USR,
                   oi.FIRST_NAME I_NAME, oi.EMAIL I_EMAIL,
                   null as I_EMAIL_NOTFS,
                   oi.COUNTRY I_WHERE, cast('' as varchar(100)) I_WEBSITE
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
            -- union
            -- Twitter tables
            -- Facebook tables
            )
        select i.ID_TYPE, i.I_ID,
            i.I_NAME, i.I_EMAIL,
            case
              -- Can only be non-null for IdentitySimple.
              when i.I_EMAIL_NOTFS is not null then i.I_EMAIL_NOTFS
              else u.EMAIL_NOTFS end  -- might be null
              EMAIL_NOTFS,
            i.I_WHERE, i.I_WEBSITE,
            u.SNO U_ID,
            u.DISPLAY_NAME U_DISP_NAME,
            u.EMAIL U_EMAIL,
            u.COUNTRY U_COUNTRY,
            u.WEBSITE U_WEBSITE,
            u.SUPERADMIN U_SUPERADMIN
        from identities i left join DW1_USERS u on
              u.SNO = i.I_USR and
              u.TENANT = ?
        """, args ::: List(tenantId), rs => {
      var usersById = mut.HashMap[String, User]()
      var identities = List[Identity]()
      while (rs.next) {
        val idId = rs.getString("I_ID")
        var userId = rs.getLong("U_ID").toString  // 0 if null
        var user: Option[User] = None
        assErrIf(idId isEmpty, "[debiki_error_392Qvc89]")
        val emailPrefs = _fromFlagEmailNotfs(rs.getString("EMAIL_NOTFS"))

        identities ::= (rs.getString("ID_TYPE") match {
          case "Simple" =>
            userId = _dummyUserIdFor(idId)
            val i = IdentitySimple(
                id = idId,
                userId = userId,
                name = d2e(rs.getString("I_NAME")),
                email = d2e(rs.getString("I_EMAIL")),
                location = d2e(rs.getString("I_WHERE")),
                website = d2e(rs.getString("I_WEBSITE")))
            user = Some(_dummyUserFor(i, emailNotfPrefs = emailPrefs))
            i
          case "OpenID" =>
            assErrIf(userId isEmpty, "[debiki_error_9V86krR3]")
            IdentityOpenId(
                id = idId,
                userId = userId,
                // These uninteresting OpenID fields were never loaded.
                // COULD place them in an Option[OpenIdInfo]?
                oidEndpoint = "?",
                oidVersion = "?",
                oidRealm = "?",
                oidClaimedId = "?",
                oidOpLocalId = "?",
                firstName = n2e(rs.getString("I_NAME")),
                email = n2e(rs.getString("I_EMAIL")),
                country = n2e(rs.getString("I_WHERE")))
        })

        if (user isEmpty) user = Some(User(
            id = userId,
            displayName = n2e(rs.getString("U_DISP_NAME")),
            email = n2e(rs.getString("U_EMAIL")),
            emailNotfPrefs = emailPrefs,
            country = n2e(rs.getString("U_COUNTRY")),
            website = n2e(rs.getString("U_WEBSITE")),
            isSuperAdmin = rs.getString("U_SUPERADMIN") == "T"))

        if (!usersById.contains(userId)) usersById(userId) = user.get
      }
      Full((identities,
          usersById.values.toList))  // silly to throw away hash map
    }).open_! // silly box
  }

  private def _loadMemships(tenantId: String, r: RequesterInfo
                               ): List[String] = {
    Nil
  }

  override def savePageActions[T](tenantId: String, pageGuid: String,
                                  xs: List[T]): Box[List[T]] = {
    db.transaction { implicit connection =>
      _insert(tenantId, pageGuid, xs)
    }
  }

  def loadPage(tenantId: String, pageGuid: String): Box[Debate] = {
    /*
    db.transaction { implicit connection =>
      // BUG: There might be a NPE / None.get because of phantom reads.
      // Prevent phantom reads from DW1_ACTIONS. (E.g. rating tags are read
      // from DW1_RATINGS before _ACTIONS is considered, and another session
      // might insert a row into _ACTIONS just after _RATINGS was queried.)
      connection.setTransactionIsolation(
        Connection.TRANSACTION_SERIALIZABLE)
      */

    var pageSno = ""
    var logins = List[Login]()

    // Load all logins for pageGuid. Load DW1_PAGES.SNO at the same time
    // (although it's then included on each row) to avoid db roundtrips.
    db.queryAtnms("""
        select p.SNO PAGE_SNO,
            l.SNO LOGIN_SNO, l.PREV_LOGIN,
            l.ID_TYPE, l.ID_SNO,
            l.LOGIN_IP, l.LOGIN_TIME,
            l.LOGOUT_IP, l.LOGOUT_TIME
        from DW1_TENANTS t, DW1_PAGES p, DW1_PAGE_ACTIONS a, DW1_LOGINS l
        where t.ID = ?
          and p.TENANT = t.ID
          and p.GUID = ?
          and a.PAGE = p.SNO
          and a.LOGIN = l.SNO""", List(tenantId, pageGuid), rs => {
      while (rs.next) {
        pageSno = rs.getString("PAGE_SNO")
        val loginId = rs.getLong("LOGIN_SNO").toString
        val prevLogin = Option(rs.getLong("PREV_LOGIN")).map(_.toString)
        val ip = rs.getString("LOGIN_IP")
        val date = ts2d(rs.getTimestamp("LOGIN_TIME"))
        // ID_TYPE need not be remembered, since each ID_SNO value
        // is unique over all DW1_LOGIN_OPENID/SIMPLE/... tables.
        // (So you'd find the appropriate IdentitySimple/OpenId by doing
        // People.identities.find(_.id = x).)
        val idId = rs.getLong("ID_SNO").toString
        logins ::= Login(id = loginId, prevLoginId = prevLogin, ip = ip,
                        date = date, identityId = idId)
      }
      Empty
    })

    val (identities, users) =
        _loadUsers(onPageWithSno = pageSno, tenantId = tenantId)

    // Load rating tags.
    val ratingTags: mut.HashMap[String, List[String]] = db.queryAtnms("""
        select a.PAID, r.TAG from DW1_PAGE_ACTIONS a, DW1_PAGE_RATINGS r
        where a.TYPE = 'Rating' and a.PAGE = ? and a.PAGE = r.PAGE and
              a.PAID = r.PAID
        order by a.PAID
        """,
      List(pageSno.toString), rs => {
        val map = mut.HashMap[String, List[String]]()
        var tags = List[String]()
        var curPaid = ""  // current page action id

        while (rs.next) {
          val paid = rs.getString("PAID");
          val tag = rs.getString("TAG");
          if (curPaid isEmpty) curPaid = paid
          if (paid == curPaid) tags ::= tag
          else {
            // All tags found for the rating with _ACTIONS.PAID = curPaid.
            map(curPaid) = tags
            tags = tag::Nil
            curPaid = paid
          }
        }
        if (tags.nonEmpty)
          map(curPaid) = tags

        Full(map)
      }).open_!  // COULD throw exceptions, don't use boxes?

    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select PAID, LOGIN, TIME, TYPE, RELPA,
              TEXT, MARKUP, WHEERE, NEW_IP
        from DW1_PAGE_ACTIONS
        where PAGE = ?
        order by TIME desc
        """,
        List(pageSno.toString), rs => {
      var actions = List[AnyRef]()
      while (rs.next) {
        val id = rs.getString("PAID");
        val loginSno = rs.getLong("LOGIN").toString
        val time = ts2d(rs.getTimestamp("TIME"))
        val typee = rs.getString("TYPE");
        val relpa = rs.getString("RELPA")
        val text_? = rs.getString("TEXT")
        val markup_? = rs.getString("MARKUP")
        val where_? = rs.getString("WHEERE")
        val newIp = Option(rs.getString("NEW_IP"))

        val action = typee match {
          case post if post == "Post" || post == "Meta" =>
            // How repr empty root post parent? ' ' or '-' or '_' or '0'?
            new Post(id = id, parent = relpa, date = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?),
              markup = n2e(markup_?), isMeta = post == "Meta",
              where = Option(where_?))
          case "Rating" =>
            val tags = ratingTags(id)
            new Rating(id = id, postId = relpa, date = time,
              loginId = loginSno, newIp = newIp, tags = tags)
          case "Edit" =>
            new Edit(id = id, postId = relpa, date = time,
              loginId = loginSno, newIp = newIp, text = n2e(text_?))
          case "EditApp" =>
            new EditApp(id = id, editId = relpa, date = time,
              loginId = loginSno, newIp = newIp,
              result = n2e(text_?))
          case flag if flag startsWith "Flag" =>
            val reasonStr = flag drop 4 // drop "Flag"
            val reason = FlagReason withName reasonStr
            Flag(id = id, postId = relpa, loginId = loginSno, newIp = newIp,
                date = time, reason = reason, details = n2e(text_?))
          case delete if delete startsWith "Del" =>
            val wholeTree = delete match {
              case "DelTree" => true
              case "DelPost" => false
              case x => assErr("[debiki_error_0912k22]")
            }
            Delete(id = id, postId = relpa, loginId = loginSno, newIp = newIp,
                date = time, wholeTree = wholeTree, reason = n2e(text_?))
          case x => return Failure(
              "Bad DW1_ACTIONS.TYPE: "+ safed(typee) +" [debiki_error_Y8k3B]")
        }
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Full(Debate.fromActions(guid = pageGuid,
          logins, identities, users, actions))
    })
  }

  def loadTemplates(perhapsTmpls: List[PagePath]): List[Debate] = {
    // TODO: TRANSACTION_SERIALIZABLE, or someone might delete
    // a template after its guid has been looked up.
    if (perhapsTmpls isEmpty) return Nil
    val tenantId = perhapsTmpls.head.tenantId
    // For now, do 1 lookup per location.
    // Minor bug: if template /some-page.tmpl does not exist, but there's
    // an odd page /some-page.tmpl/, then that *page* is found and
    // returned although it's probably not a template.  ? Solution:
    // TODO disallow '.' in directory names? but allow e.g. style.css,
    // scripts.js, template.tpl.
    var guids = List[String]()
    perhapsTmpls map { tmplPath =>
      assert(tmplPath.tenantId == tenantId)
      _findCorrectPagePath(tmplPath) match {
        case Full(pagePath) => guids ::= pagePath.guid.get
        case Empty => // fine, template does not exist
        case f: Failure => error(
          "Error loading template guid [debiki_error_309sU32]:\n"+ f)
      }
    }
    val pages: List[Debate] = guids.reverse map { guid =>
      loadPage(tenantId, guid) match {
        case Full(d: Debate) => d
        case x =>
          // Database inaccessible? If I fixed TRANSACTION_SERIALIZABLE
          // (see above) the template would have been found for sure.
          val err = "Error loading template [debiki_error_983keCK31]"
          logger.error(err +":"+ x.toString) // COULD fix consistent err reprt
          error(err)
      }
    }
    pages
  }

  def createTenant(name: String): Tenant = {
    db.transaction { implicit connection =>
      val tenantId = db.nextSeqNo("DW1_TENANTS_ID").toString
      db.update("""
          insert into DW1_TENANTS (ID, NAME)
          values (?, ?)
          """, List(tenantId, name))
      Full(Tenant(id = tenantId, name = name, hosts = Nil))
    }.open_!
  }

  def addTenantHost(tenantId: String, host: TenantHost) = {
    db.transaction { implicit connection =>
      val cncl = if (host.isCanonical) "T" else "F"
      val https = host.https match {
        case TenantHost.HttpsRequired => "Required"
        case TenantHost.HttpsAllowed => "Allowed"
        case TenantHost.HttpsNone => "No"
      }
      db.update("""
          insert into DW1_TENANT_HOSTS (TENANT, HOST, CANONICAL, HTTPS)
          values (?, ?, ?, ?)
          """,
          List(tenantId, host.address, cncl, https))
    }
  }

  def lookupTenant(scheme: String, host: String): TenantLookup = {
    db.queryAtnms("""
        select t.TENANT TID,
            t.CANONICAL THIS_CANONICAL, t.HTTPS THIS_HTTPS,
            c.HOST CANONICAL_HOST, c.HTTPS CANONICAL_HTTPS
        from DW1_TENANT_HOSTS t, -- this host, the one connected to
            DW1_TENANT_HOSTS c  -- the cannonical host
        where t.HOST = ?
          and c.TENANT = t.TENANT
          and c.CANONICAL = 'T'
        """, List(host), rs => {
      if (!rs.next) return FoundNothing
      val tenantId = rs.getString("TID")
      val thisIsChost = rs.getString("THIS_CANONICAL") == "T"
      val thisHttps = rs.getString("THIS_HTTPS")
      val chost = rs.getString("CANONICAL_HOST")
      val chostHttps = rs.getString("CANONICAL_HTTPS")
      def chostUrl =  // the canonical host URL, e.g. http://www.example.com
          (if (chostHttps == "Required") "https://" else "http://") + chost
      assErrIf(thisIsChost != (host == chost), "[debiki_error_98h2kwi1215]")

      def useThisHostAndScheme = FoundChost(tenantId)
      def redirect = FoundAlias(tenantId, canonicalHostUrl = chostUrl,
                              shouldRedirect = true)
      def useLinkRelCanonical = redirect.copy(shouldRedirect = false)

      Full((thisIsChost, scheme, thisHttps) match {
        case (true, "http" , "Required") => redirect
        case (true, "http" , _         ) => useThisHostAndScheme
        case (true, "https", "Required") => useThisHostAndScheme
        case (true, "https", "Allowed" ) => useLinkRelCanonical
        case (true, "https", "No"      ) => redirect
        case (false, _     , _         ) => redirect
      })
    }).open_!
  }

  def checkPagePath(pathToCheck: PagePath): Box[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def loadPermsOnPage(reqInfo: RequestInfo): (RequesterInfo, PermsOnPage) = {
    // Currently all permissions are actually hardcoded in this function.
    // (There's no permissions db table.)

    /*
    The algorithm: (a sketch. And not yet implemented)
    lookup rules in PATHRULES:  (not implemented! paths hardcoded instead)
      if guid, try:  parentFolder / -* /   (i.e. any guid in folder)
      else, try:
        first: parentFolder / pageName /   (this particular page)
        then:  parentFolder / * /          (any page in folder)
      Then continue with the parent folders:
        first: parentsParent / parentFolderName /
        then: parentsParent / * /
      and so on with the parent's parent ...
    */

    val requester = _loadRequesterInfo(reqInfo)
    def user = requester.user

    // ?? Replace admin test with:
    // if (requeuster.memships.contains(AdminGroupId)) return PermsOnPage.All

    // Allow superadmins to do anything, e.g. create pages anywhere.
    // (Currently users can edit their own pages only.)
    if (user.map(_.isSuperAdmin) == Some(true))
      return (requester, PermsOnPage.All)

    // For now, hide .js and .css and .tmpl files for everyone but superadmins.
    // (If people can *edit* them, they could conduct xss attacks.)
    if (reqInfo.pagePath.name.contains('.'))
      return (requester, PermsOnPage.None)

    // Non-admins can only create pages whose names are prefixed
    // with their guid, like so: /folder/-guid-pagename.
    // (Currently there are no admin users, only superadmins)
    // This is done by invoking /folder/?createpage.
    // Admins can do this: /folder/page-name?createpage
    (reqInfo.doo, reqInfo.pagePath.isFolderPath) match {
      case (Do.Create, true) => () // a page wil be created in this folder
      case (Do.Create, false) =>
        // A page name was specified, not a folder. Deny.
        return (requester, PermsOnPage.None)
      case _ => ()
    }

    // For now, hardcode rules here:
    val mayCreatePage = {
      val p = reqInfo.pagePath.path
      if (p == "/test/") true
      else if (p == "/allt/") true
      else if (p == "/forum/") true
      else if (p == "/wiki/") true
      else false
    }

    val isWiki = reqInfo.pagePath.folder == "/wiki/"

    (requester, PermsOnPage.Wiki.copy(
      createPage = mayCreatePage,
      editPage = isWiki,
      // Authenticated users can edit others' comments.
      // (In the future, the reputation system (not implemented) will make
      // them lose this ability should they misuse it.)
      editAnyReply = isWiki || user.map(_.isAuthenticated) == Some(true)
      ))
  }

  def saveInboxSeeds(tenantId: String, seeds: Seq[InboxSeed]) {
    db.transaction { implicit connection =>
      val valss: List[List[AnyRef]] = (for (seed <- seeds.toList) yield {
        List(tenantId, seed.roleId, seed.pageId, seed.pageActionId,
          seed.sourceActionId, seed.ctime)
      }).toList
      val valss2: List[List[AnyRef]] = for (seed <- seeds.toList) yield {
        List(tenantId, seed.roleId,
            tenantId, seed.pageId,
            seed.pageActionId, seed.sourceActionId, seed.ctime)
      }
      db.batchUpdate("""
          insert into DW1_ROLE_INBOX(
              TENANT, ROLE,
              PAGE,
              TARGET_PAID, SOURCE_PAID, CTIME)
            values (?, ?,
              (select SNO from DW1_PAGES where TENANT = ? and GUID = ?),
              ?, ?, ?)
          """,
          valss2)
      Empty  // my stupid API, should rewrite
    }
  }

  def loadInboxItems(tenantId: String, roleId: String): List[InboxItem] = {
    // COULD load inbox items for all granted roles too.
    var items = List[InboxItem]()
    db.queryAtnms("""
        select a.TYPE, a.TEXT, p.GUID PAGE, a.PAID, b.CTIME, b.SOURCE_PAID
        from DW1_ROLE_INBOX b inner join DW1_PAGE_ACTIONS a
          on b.PAGE = a.PAGE and b.TARGET_PAID = a.PAID
          inner join DW1_PAGES p
          on b.PAGE = p.SNO
        where b.TENANT = ? and b.ROLE = ?
        order by b.CTIME desc
        limit 50
        """,
        List(tenantId, roleId),
        rs => {
      while (rs.next) {
        val tyype = rs.getString("TYPE")
        val text = rs.getString("TEXT")
        val pageId = rs.getString("PAGE")
        val pageActionId = rs.getString("PAID")
        val ctime = ts2d(rs.getTimestamp("CTIME"))
        val sourceActionId = rs.getString("SOURCE_PAID")
        val item = tyype match {
          case "Post" => InboxItem(
            tyype = Do.Reply,
            title = "?",  // COULD extract title somehow somewhere?
            summary = text.take(100),
            pageId = pageId,
            pageActionId = pageActionId,
            sourceActionId = sourceActionId,
            ctime = ctime)
          case x =>
            unimplemented("Loading inbox item of type "+ safed(x))
        }
        items ::= item
      }
      Empty // dummy
    })

    items
  }

  def configRole(tenantId: String, loginId: String, ctime: ju.Date,
                 roleId: String, emailNotfPrefs: EmailNotfPrefs) {
    // Currently auditing not implemented for the roles/users table,
    // so loginId and ctime aren't used.
    db.transaction { implicit connection =>
      db.update("""
          update DW1_USERS
          set EMAIL_NOTFS = ?
          where TENANT = ? and SNO = ? and
              (EMAIL_NOTFS is null or EMAIL_NOTFS <> 'F')
          """,
          List(_toFlag(emailNotfPrefs), tenantId, roleId))
    }
  }

  def configIdtySimple(tenantId: String, loginId: String, ctime: ju.Date,
                       emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
    db.transaction { implicit connection =>
      // Mark the current row as 'O' (old) -- unless EMAIL_NOTFS is 'F'
      // (Forbidden Forever). Then leave it as is, and let the insert
      // below fail.
      // COULD check # rows updated? No, there might be no rows to update.
      db.update("""
          update DW1_IDS_SIMPLE_EMAIL
          set VERSION = 'O' -- old
          where TENANT = ? and EMAIL = ? and VERSION = 'C'
            and EMAIL_NOTFS != 'F'
          """,
          List(tenantId, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into DW1_IDS_SIMPLE_EMAIL (
              TENANT, LOGIN, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, ?, 'C', ?, ?)
          """,
          List(tenantId, loginId, d2ts(ctime), emailAddr,
              _toFlag(emailNotfPrefs)))
    }
  }

  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath): Box[PagePath] = {
    var query = """
        select FOLDER, PAGE_GUID, PAGE_NAME, GUID_IN_PATH
        from DW1_PATHS
        where TENANT = ?
        """
    var binds = List(pagePathIn.tenantId)
    var maxRowsFound = 1  // there's a unique key
    pagePathIn.guid match {
      case Some(guid) =>
        query += " and PAGE_GUID = ?"
        binds ::= guid
      case None =>
        // GUID_IN_PATH = 'F' means that the page guid must not be part
        // of the page url. ((So you cannot look up [a page that has its guid
        // as part of its url] by searching for its url without including
        // the guid. Had that been possible, many pages could have been found
        // since pages with different guids can have the same name.
        // Hmm, could search for all pages, as if the guid hadn't been
        // part of their name, and list all pages with matching names?))
        query += """
            and GUID_IN_PATH = 'F'
            and (
              (FOLDER = ? and PAGE_NAME = ?)
            """
        binds ::= pagePathIn.folder
        binds ::= e2s(pagePathIn.name)
        // Try to correct bad URL links.
        // COULD skip (some of) the two if tests below, if action is ?newpage.
        // (Otherwise you won't be able to create a page in
        // /some/path/ if /some/path already exists.)
        if (pagePathIn.name nonEmpty) {
          // Perhaps the correct path is /folder/page/ not /folder/page.
          // Try that path too. Choose sort orter so /folder/page appears
          // first, and skip /folder/page/ if /folder/page is found.
          query += """
              or (FOLDER = ? and PAGE_NAME = ' ')
              )
            order by length(FOLDER) asc
            """
          binds ::= pagePathIn.folder + pagePathIn.name +"/"
          maxRowsFound = 2
        }
        else if (pagePathIn.folder.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += """
              or (FOLDER = ? and PAGE_NAME = ?)
              )
            order by length(FOLDER) desc
            """
          val perhapsPath = pagePathIn.folder.dropRight(1)  // drop `/'
          val lastSlash = perhapsPath.lastIndexOf("/")
          val (shorterPath, nonEmptyName) = perhapsPath.splitAt(lastSlash + 1)
          binds ::= shorterPath
          binds ::= nonEmptyName
          maxRowsFound = 2
        }
        else {
          query += ")"
        }
    }
    db.queryAtnms(query, binds.reverse, rs => {
      var correctPath: Box[PagePath] = Empty
      if (rs.next) {
        correctPath = Full(pagePathIn.copy(  // keep pagePathIn.tenantId
            folder = rs.getString("FOLDER"),
            guid = Some(rs.getString("PAGE_GUID")),
            guidInPath = rs.getString("GUID_IN_PATH") == "T",
            // If there is a root page ("serveraddr/") with no name,
            // it is stored as a single space; s2e removes such a space:
            name = s2e(rs.getString("PAGE_NAME"))))
      }
      assert(maxRowsFound == 2 || !rs.next)
      correctPath
    })
  }

  private def _createPage[T](where: PagePath, debate: Debate)
                            (implicit conn: js.Connection): Box[Int] = {
    db.update("""
        insert into DW1_PAGES (SNO, TENANT, GUID)
        values (nextval('DW1_PAGES_SNO'), ?, ?)
        """, List(where.tenantId, debate.guid))

    // Concerning prefixing the page name with the page guid:
    // /folder/?createpage results in the guid prefixing the page name, like so:
    //    /folder/-guid-pagename
    // but /folder/pagename?createpage results in the guid being hidden,
    // and this'll be the path to the page:
    //    /folder/pagename
    val guidInPath = if (where.isFolderPath) "T" else "F"

    db.update("""
        insert into DW1_PATHS (TENANT, FOLDER, PAGE_GUID,
                                   PAGE_NAME, GUID_IN_PATH)
        values (?, ?, ?, ?, ?)
        """,
        List(where.tenantId, where.folder, debate.guid, e2s(where.name),
            guidInPath))
  }

  private def _insert[T](
        tenantId: String, pageGuid: String, xs: List[T])
        (implicit conn: js.Connection): Box[List[T]] = {
    var xsWithIds = Debate.assignIdsTo(
                      xs.asInstanceOf[List[AnyRef]]).asInstanceOf[List[T]]
    var bindPos = 0
    for (x <- xsWithIds) {
      // Could optimize:  (but really *not* important!)
      // Use a CallableStatement and `insert into ... returning ...'
      // to create the _ACTIONS row and read the SNO in one single roundtrip.
      // Or select many SNO:s in one single query? and then do a batch
      // insert into _ACTIONS (instead of one insert per row), and then
      // batch inserts into other tables e.g. _RATINGS.
      // Also don't need to select pageSno every time -- but probably better to
      // save 1 roundtrip: usually only 1 row is inserted at a time (an end
      // user posts 1 reply at a time).
      val pageSno = db.query("""
          select SNO from DW1_PAGES
            where TENANT = ? and GUID = ?
          """, List(tenantId, pageGuid), rs => {
        rs.next
        Full(rs.getLong("SNO").toString)
      }).open_!

      val insertIntoActions = """
          insert into DW1_PAGE_ACTIONS(LOGIN, PAGE, PAID, TIME, TYPE,
                                    RELPA, TEXT, MARKUP, WHEERE)
          values (?, ?, ?, ?, ?, ?, ?, ?, ?)
          """
      // Keep in mind that Oracle converts "" to null.
      val commonVals = Nil // p.loginId::pageSno::Nil
      x match {
        case p: Post =>
          val markup = "" // TODO
          val tyype = if (p.isMeta) "Meta" else "Post"
          db.update(insertIntoActions, commonVals:::List(
            p.loginId, pageSno, p.id, p.date, tyype,
            p.parent, p.text, markup, e2n(p.where)))
        case r: Rating =>
          db.update(insertIntoActions, commonVals:::List(
            r.loginId, pageSno, r.id, r.date, "Rating", r.postId,
            "", "", ""))
          db.batchUpdate("""
            insert into DW1_PAGE_RATINGS(PAGE, PAID, TAG) values (?, ?, ?)
            """, r.tags.map(t => List(pageSno, r.id, t)))
        case e: Edit =>
          db.update(insertIntoActions, commonVals:::List(
            e.loginId, pageSno, e.id, e.date, "Edit",
            e.postId, e.text, "", ""))
        case a: EditApp =>
          db.update(insertIntoActions, commonVals:::List(
            a.loginId, pageSno, a.id, a.date, "EditApp",
            a.editId, a.result, "", ""))
        case f: Flag =>
          db.update(insertIntoActions, commonVals:::List(
            f.loginId, pageSno, f.id, f.date, "Flag" + f.reason,
            f.postId, f.details, "", ""))
        case d: Delete =>
          db.update(insertIntoActions, commonVals:::List(
            d.loginId, pageSno, d.id, d.date,
            "Del" + (if (d.wholeTree) "Tree" else "Post"),
            d.postId, d.reason, "", ""))
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [debiki_error_38rkRF]")
      }
    }
    Full(xsWithIds)
  }

  def _dummyUserIdFor(identityId: String) = "-"+ identityId

  def _dummyUserFor(identity: IdentitySimple, emailNotfPrefs: EmailNotfPrefs,
                    id: String = null): User = {
    User(id = (if (id ne null) id else identity.userId),
      displayName = identity.name, email = identity.email,
      emailNotfPrefs = emailNotfPrefs,
      country = "",
      website = identity.website, isSuperAdmin = false)
  }

  def _toFlag(prefs: EmailNotfPrefs): String = prefs match {
    case EmailNotfPrefs.Receive => "R"
    case EmailNotfPrefs.DontReceive => "N"
    case EmailNotfPrefs.ForbiddenForever => "F"
    case x =>
      warnDbgDie("Bad EmailNotfPrefs value: "+ safed(x) +
          " [debiki_error_0EH43k8]")
      "N"  // fallback to no email
  }

  def _fromFlagEmailNotfs(flag: String): EmailNotfPrefs = flag match {
    case null =>
      // Don't send email unless the user has actively choosen to
      // receive emails (the user has made no choice in this case).
      EmailNotfPrefs.DontReceive
    case "R" => EmailNotfPrefs.Receive
    case "N" => EmailNotfPrefs.DontReceive
    case "F" => EmailNotfPrefs.ForbiddenForever
    case x =>
      warnDbgDie("Bad EMAIL_NOTFS: "+ safed(x) +" [debiki_error_6ie53k011]")
      EmailNotfPrefs.DontReceive
  }

  /** Adds a can be Empty Prefix.
   *
   * Oracle converts the empty string to NULL, so prefix strings that might
   * be empty with a magic value, and remove it when reading data from
   * the db.
   */
  //private def _aep(str: String) = "-"+ str

  /** Removes a can be Empty Prefix. */
  //private def _rep(str: String) = str drop 1

  // TODO:
  /*
  From http://www.exampledepot.com/egs/java.sql/GetSqlWarnings.html:

    // Get warnings on Connection object
    SQLWarning warning = connection.getWarnings();
    while (warning != null) {
        // Process connection warning
        // For information on these values, see Handling a SQL Exception
        String message = warning.getMessage();
        String sqlState = warning.getSQLState();
        int errorCode = warning.getErrorCode();
        warning = warning.getNextWarning();
    }

    // After a statement has been used:
    // Get warnings on Statement object
    warning = stmt.getWarnings();
    if (warning != null) {
        // Process statement warnings...
    }

  From http://www.exampledepot.com/egs/java.sql/GetSqlException.html:

    try {
        // Execute SQL statements...
    } catch (SQLException e) {
        while (e != null) {
            // Retrieve a human-readable message identifying the reason
            // for the exception
            String message = e.getMessage();

            // This vendor-independent string contains a code that identifies
            // the reason for the exception.
            // The code follows the Open Group SQL conventions.
            String sqlState = e.getSQLState();

            // Retrieve a vendor-specific code identifying the reason for
            // the  exception.
            int errorCode = e.getErrorCode();

            // If it is necessary to execute code based on this error code,
            // you should ensure that the expected driver is being
            // used before using the error code.

            // Get driver name
            String driverName = connection.getMetaData().getDriverName();
            if (driverName.equals("Oracle JDBC Driver") && errorCode == 123) {
                // Process error...
            }

            // The exception may have been chained; process the next
            // chained exception
            e = e.getNextException();
        }
    }
   */
}

/*
class AllStatements(con: js.Connection) {
  con.setAutoCommit(false)
  con.setAutoCommit(true)
  // "It is advisable to disable the auto-commit mode only during th
  // transaction mode. This way, you avoid holding database locks for
  // multiple statements, which increases the likelihood of conflicts
  // with other users."
  // <http://download.oracle.com/javase/tutorial/jdbc/basics/transactions.html>



  val loadPage = con.prepareStatement("""
select 1 from dual
""")
  // ResultSet = updateSales.executeUpdate()
  // <an int value that indicates how many rows of a table were updated>
  //  = updateSales.executeQuery()
  // (DDL statements return 0)
  // // con.commit()
  // con.setAutoCommit(false)

  val purgePage = con.prepareStatement("""
select 1 from dual
""")

  val loadUser = con.prepareStatement("""
select 1 from dual
""")
}
*/

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list