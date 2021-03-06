package views.html

import controllers.routes.{Assets => AuthAssets}
import play.api.data.Field
import views.html.htmlForm.bootstrap3.HtmlForm._

object passwordStrength {
  def apply(field: Field, label: String="", maybePlaceholder: Option[String]=None, style: String = ""): String = {
    val widget = inputter(
      field = field,
      label = label,
      maybePlaceholder = maybePlaceholder,
      isPassword = true,
      data = List("pwd" -> "true"),
      style = style
    )
    s"""<section class="password-strength-section">
       |$widget
       |<meter max="4" id="password-strength-meter" value=""></meter>
       | <p id="password-strength-text"></p>
       |</section>
       |<script src="https://cdnjs.cloudflare.com/ajax/libs/zxcvbn/4.2.0/zxcvbn.js"></script>
       |<script src="${ AuthAssets.at("javascripts/zxcvbnShim.js") }"></script>
       |""".stripMargin
  }
}
