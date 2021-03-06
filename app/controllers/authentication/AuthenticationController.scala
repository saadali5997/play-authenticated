package controllers.authentication

import java.net.URL
import java.util.UUID
import javax.inject.Inject
import auth.{AuthForms, Authentication, PasswordHasher, SignUpData, UnauthorizedHandler}
import auth.AuthForms._
import com.micronautics.Smtp
import org.webjars.play.WebJarAssets
import controllers.routes.{ApplicationController => AppRoutes}
import controllers.authentication.routes.{AuthenticationController => AuthRoutes}
import model.dao.{AuthTokens, Users}
import model.persistence.Id
import model.{AuthToken, EMail, User, UserId}
import org.joda.time.DateTime
import play.api.data.Form
import play.api.i18n.{I18nSupport, Lang, Messages, MessagesApi}
import play.api.mvc.Results.Unauthorized
import play.api.mvc.{Action, AnyContent, MessagesAbstractController, MessagesControllerComponents, MessagesRequest, RequestHeader, Result}
import play.twirl.api.Html
import service.AuthTokenScheduler
import views.html._
import views.html.htmlForm.CSRFHelper
import scala.concurrent.{ExecutionContext, Future}

object AuthenticationController {
  protected def sendEmail(toUser: User, subject: String)(bodyFragment: String)
                         (implicit smtp: Smtp): Unit = {
    val completeBody = s"""<html>
                          |  <body>
                          |    <p>Dear ${ toUser.fullName },</p>
                          |    $bodyFragment
                          |    <p>Thank you,<br/>
                          |      ${ smtp.smtpFrom }</p>
                          |</body>
                          |</html
                          |""".stripMargin

    EMail.send(
      to = toUser.email,
      cc = Nil, // todo add cc to conf
      bcc = Nil,
      subject = subject
    )(body = completeBody)
  }

  def sendActivateAccountEmail(toUser: User, url: URL, expires: DateTime)
                              (implicit messages: Messages, smtp: Smtp): Unit =
    sendEmail(toUser=toUser, subject=messages("email.activate.account.subject", url.getHost, expires)) {
      val message = messages("email.activate.account.html.text", url, AuthToken.fmt.print(expires))
      s"<p>$message</p>\n"
    }

  def sendAlreadySignUpEMail(toUser: User, url: URL)
                            (implicit messages: Messages, smtp: Smtp): Unit =
    sendEmail(toUser=toUser, subject=messages("email.already.signed.up.subject")) {
      s"<p>${ messages("email.already.signed.up.html.text", url) }</p>\n"
    }

  def sendResetPasswordEMail(toUser: User, url: URL)
                            (implicit messages: Messages, smtp: Smtp): Unit =
     sendEmail(toUser=toUser, subject=messages("email.reset.password.subject")) {
       s"<p>${ messages("email.reset.password.html.text", url) }</p>\n"
     }

  def sendSignUpEMail(toUser: User, url: URL, expires: DateTime)
                     (implicit messages: Messages, smtp: Smtp): Unit =
     sendEmail(toUser=toUser, subject=messages("email.sign.up.subject")) {
       s"<p>${ messages("email.sign.up.html.text", url, expires) }</p>\n"
     }
}

/** Signup steps:
  *   - `signUpShow`
  *   - `signUpSave`
  *   - `signUpAwaitConfirmation`
  *   - `signUpActivateUser` (user is logged in and redirected to welcome page)
  *
  * Login steps:
  *   - `loginShow`
  *   - `loginSubmit`
  *
  * Logout steps:
  *   - `logout`
  *
  * Change password steps:
  *   - `passwordChangeShow`
  *   - `passwordChangeSubmit`
  *
  * Reset password steps:
  *   - `passwordResetShow`
  *   - `passwordResetSubmit`
  *
  * Forgot password steps:
  *   - `passwordForgotShow`
  *   - `passwordForgotSubmit` */
class AuthenticationController @Inject()(
  authentication: Authentication,
  authTokenScheduler: AuthTokenScheduler,
  mcc: MessagesControllerComponents,
  unauthorizedHandler: UnauthorizedHandler
)(implicit
  csrfHelper: CSRFHelper,
  ec: ExecutionContext,
  users: Users,
  webJarsUtil: org.webjars.play.WebJarsUtil
) extends MessagesAbstractController(mcc) with I18nSupport {
  import authentication._
  implicit lazy val smtp: Smtp = EMail.smtp

  /** User login step 1/2 */
  def loginShow: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    Ok(login(loginForm))
  }

  /** User login step 2/2 */
  def loginSubmit: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    loginForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(login(formWithErrors)),
      loginData => {
        users
          .findByUserId(loginData.userId)
          .filter(_.passwordMatches(loginData.password))
          .map {
            case user if user.activated =>
              Redirect(AuthRoutes.showAccountDetails())
                .withSession("userId" -> user.userId.value)

            case user =>
              val formWithError = AuthForms.loginForm.withError("error",
                s"""You have not yet activated this account.
                   |Please find the email sent to ${ user.email } from ${ smtp.smtpFrom },
                   |and click on the link in the email so this account will be activated.
                   |""".stripMargin)
              Unauthorized(login(formWithError))
          }.getOrElse {
            unauthorizedHandler.onUnauthorized(request)
          }
      }
    )
  }

  def logout: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    Redirect(routes.AuthenticationController.loginShow())
      .withNewSession
      .flashing("warning" -> "You've been logged out. Log in again below:")
  }

  /** Password change step 1/2 */
  def passwordChangeShow: Action[AnyContent] = SecuredAction { implicit request =>
    Ok(passwordChange(changePasswordForm))
  }

  /** Password change step 2/2 */
  def passwordChangeSubmit: Action[AnyContent] = SecuredAction { implicit request =>
    changePasswordForm.bindFromRequest.fold(
      formWithErrors => BadRequest(passwordChange(formWithErrors)),
      changePasswordData => {
        val hashedPassword = PasswordHasher.hash(changePasswordData.newPassword)
        users.update(request.user.copy(password = hashedPassword))
        Redirect(AppRoutes.securedAction()) // redirect to any secured page to indicate success
          .flashing("success" -> Messages("password.changed"))
      }
    )
  }

  /** Forgot Password step 1/2. */
  def passwordForgotShow: Action[AnyContent] = Action.async { implicit request: MessagesRequest[AnyContent] =>
    Future.successful(Ok(passwordForgot(AuthForms.forgotPasswordForm)))
  }

  /** Forgot Password step 2/2.
    * Sends an email with password reset instructions to the given address if it exists in the database.
    * If any failure, enforce security by not showing the user any existing `userIds`. */
  def passwordForgotSubmit: Action[AnyContent] = Action.async { implicit request: MessagesRequest[AnyContent] =>
    Future {
      lazy implicit val lang: Lang = implicitly[Lang] // Uses Lang.defaultLang
      val messages = implicitly[Messages] // Uses I18nSupport.lang2messages(Lang.defaultLang)
      AuthForms.forgotPasswordForm.bindFromRequest.fold(
        formWithErrors => BadRequest(passwordForgot(formWithErrors)),
        forgotPasswordData => {
          users.findByUserId(forgotPasswordData.userId) match {
            case Some(user) =>
              val (key, value, maybeAuthToken) = AuthTokens.create(user.id, authTokenScheduler.expires)
              maybeAuthToken.map { authToken =>
                val url: String = AuthRoutes.passwordResetShow(authToken.id).absoluteURL
                EMail.send(
                  to = user.email,
                  subject = request.messages("email.reset.password.subject")
                ) {
                  s"""<html>
                     |<body>
                     |  <p>${ messages("email.reset.password.hello", user.fullName) }</p>
                     |  <p>${ Html(messages("email.reset.password.html.text", url, AuthToken.fmt.print(authToken.expiry))) }</p>
                     |</body>
                     |</html>
                     |""".stripMargin
                }
                Redirect(AuthRoutes.loginShow())
                  .flashing("success" -> request.messages("reset.email.sent"))
              }.getOrElse {
                Redirect(AuthRoutes.loginShow())
                  .flashing(key -> value)
              }

            case None =>
              Redirect(AuthRoutes.loginShow())
                .flashing("error" -> "No user is associated with that userId")
          }
        }
      )
    }
  }

  /** Reset Password step 1/2.
   * @param tokenId The token id that identifies a user. */
  def passwordResetShow(tokenId: Id[UUID]): Action[AnyContent] = Action.async { implicit request: MessagesRequest[AnyContent] =>
    Future {
      AuthTokens.findById(tokenId).filter(_.isValid) match {
        case Some(_) =>
          Ok(passwordReset(AuthForms.resetPasswordForm, tokenId))

        case None =>
          Redirect(AuthRoutes.loginShow())
            .flashing("error" -> request.messages("invalid.reset.link"))
      }
    }
  }

  /** Reset Password step 2/2.
   * @param tokenId The id of the token that identifies a user. */
  def passwordResetSubmit(tokenId: Id[UUID]): Action[AnyContent] = Action.async { implicit request: MessagesRequest[AnyContent] =>
    Future {
      AuthTokens.findById(tokenId).filter(_.isValid) match {
        case Some(authToken) =>
          AuthTokens.delete(authToken)
          AuthForms.resetPasswordForm.bindFromRequest.fold(
            formWithErrors => { BadRequest(passwordReset(formWithErrors, tokenId)) },
            changePasswordData => {
                users.findById(authToken.uid) match {
                case Some(user) =>
                  users.update(user.copy(password=PasswordHasher.hash(changePasswordData.newPassword)))
                  AuthTokens.delete(authToken)
                  Redirect(AuthRoutes.loginShow())
                    .flashing("success" -> request.messages("password.reset"))

                case None =>
                  Redirect(AuthRoutes.loginShow())
                    .flashing("error" -> request.messages("invalid.reset.link"))
              }
            }
          )

        case None =>
          Redirect(AuthRoutes.loginShow())
            .flashing("error" -> request.messages("invalid.reset.link"))
      }
    }
  }

  /** Not really part of any action sequence, should be shuffled off somewhere */
  def showAccountDetails: Action[AnyContent] = SecuredAction { implicit request =>
    Ok(showUsers(request.user))
  }

  /** New user sign up step 1/4 */
  def signUpShow: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    val form: Form[SignUpData] = request.session
      .get("error")
      .map(error => signUpForm.withError("error", error))
      .getOrElse(signUpForm)
    Ok(signUp(form))
  }

  /** New user sign up step 2/4 */
  def signUpSave: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    signUpForm.bindFromRequest.fold(
      formWithErrors =>
        BadRequest(signUp(formWithErrors)),
      userData => {
        users.create(
          email     = userData.email,
          userId    = userData.userId,
          password  = userData.clearTextPassword.encrypt,
          firstName = userData.firstName,
          lastName  = userData.lastName
        ) match {
          case (k, _) if k=="success" =>
            val result: Result = sendAccountActivationEmail(userData.userId)
            result.withSession("userId" -> userData.userId.value)

          case (k, v) =>
            Redirect(AuthRoutes.signUpShow())
              .withSession(k -> v)
        }
      }
    )
  }

  /** New user sign up step 3/4 */
  def signUpAwaitConfirmation: Action[AnyContent] = Action { implicit request: MessagesRequest[AnyContent] =>
    Ok(views.html.signUpAwaitConfirmation())
  }

  /** New user sign up step 4/4.
    * Activates a User account; triggered when a user clicks on a link in an activation email.
    * @param tokenId The token that identifies a user. */
  def signUpActivateUser(tokenId: Id[UUID]): Action[AnyContent] = Action.async { implicit request: MessagesRequest[AnyContent] =>
    Future {
      val result = for {
        token <- AuthTokens.findById(tokenId)
        user  <- users.findById(token.uid)
      } yield {
        users.update(user.copy(activated=true))
        AuthTokens.delete(token)
        Redirect(AppRoutes.securedAction())
          .flashing("success" -> request.messages("account.activated"))
      }
      result.getOrElse(
        Redirect(AuthRoutes.signUpShow())
          .flashing("error" -> request.messages("invalid.activation.link"))
      )
    }
  }

  /** Sends an account activation email to the user with the given userId.
   * @param userId The userId of the user to send the activation mail to.
   * @return The result to display. */
  protected def sendAccountActivationEmail(userId: UserId)(implicit request: RequestHeader): Result = {
    @inline def successResult(key: String, value: String) =
      Redirect(AuthRoutes.signUpAwaitConfirmation())
        .flashing(key -> value)

    @inline def errorResult(userId: UserId) =
      Redirect(AuthRoutes.signUpShow())
        .flashing("error" -> s"No User found with ID $userId")

    users.findByUserId(userId) match {
      case Some(user) =>
        user.id.value.map { id =>
          val (key, value, maybeAuthToken) = AuthTokens.create(uid=Id(Some(id)), authTokenScheduler.expires)
          maybeAuthToken.map { authToken =>
            val urlStr = AuthRoutes.signUpActivateUser(authToken.id).absoluteURL()
            AuthenticationController.sendActivateAccountEmail(toUser = user, url = new java.net.URL(urlStr), authToken.expiry)
            successResult("success", Messages("activation.email.sent", user.email.value, smtp.smtpFrom))
          } getOrElse {
            successResult(key, value)
          }
        }.getOrElse(errorResult(userId))

      case None =>
        errorResult(userId)
    }
  }
}

class MyUnauthorizedHandler @Inject() (implicit
  val messagesApi: MessagesApi,
  webJarsUtil: org.webjars.play.WebJarsUtil
) extends UnauthorizedHandler with I18nSupport {
  override val onUnauthorized: RequestHeader => Result =
    request => {
      import auth.AuthForms
      implicit val req: RequestHeader = request
      Unauthorized(login(AuthForms.loginForm.withError("error", "Invalid login credentials. Please try logging in again.")))
    }
}
