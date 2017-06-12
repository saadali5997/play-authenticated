package auth

import java.util.concurrent.atomic.AtomicInteger
import currency.Currency
import model.{Email, Id, Password, UserId}
import org.joda.time.Days
import play.api.data.Forms.of
import play.api.data.format.Formats.doubleFormat
import play.api.data.format.Formatter
import play.api.data.{FormError, Mapping}

/** Play Framework form field mapping formatters.
  * To use, either mix in the `forms.FormatterLike` trait or import the `forms.Formatters` object. */
trait FormFormatterLike {
  implicit val emailFormat = new Formatter[Email] {
    /** @param key indicates the name of the form field to convert from String to EMail
      * @param data is a Map of field name -> value */
    def bind(key: String, data: Map[String, String]): Either[Seq[FormError], Email] =
      data
        .get(key)
        .map(Email.apply)
        .toRight(Seq(FormError(key, "error.required", Nil)))

    def unbind(key: String, value: Email): Map[String, String] = Map(key -> value.value)
  }

  implicit val idFormat = new Formatter[Id] {
    /** @param key indicates the name of the form field to convert from String to Id
      * @param data is a Map of field name -> value */
    def bind(key: String, data: Map[String, String]): Either[Seq[FormError], Id] =
      data
        .get(key)
        .map(k => Id.apply(k.toLong))
        .toRight(Seq(FormError(key, "error.required", Nil)))

    def unbind(key: String, value: Id): Map[String, String] = Map(key -> value.toString)
  }

  implicit val passwordFormat = new Formatter[Password] {
    /** @param key indicates the name of the form field to convert from String to EMail
      * @param data is a Map of field name -> value */
    def bind(key: String, data: Map[String, String]): Either[Seq[FormError], Password] =
      data
        .get(key)
        .map(Password.apply)
        .toRight(Seq(FormError(key, "error.required", Nil)))

    def unbind(key: String, value: Password): Map[String, String] = Map(key -> value.value)
  }

  implicit val userIdFormat = new Formatter[UserId] {
    /** @param key indicates the name of the form field to convert from String to EMail
      * @param data is a Map of field name -> value */
    def bind(key: String, data: Map[String, String]): Either[Seq[FormError], UserId] =
      data
        .get(key)
        .map(UserId.apply)
        .toRight(Seq(FormError(key, "error.required", Nil)))

    def unbind(key: String, value: UserId): Map[String, String] = Map(key -> value.value)
  }

  val eMail: Mapping[Email]              = of[Email]
  val double: Mapping[Double]            = of(doubleFormat)
  val id: Mapping[Id]                    = of[Id]
  val passwordMapping: Mapping[Password] = of[Password]
  val userId: Mapping[UserId]            = of[UserId]
}

object FormFormatters extends FormFormatterLike