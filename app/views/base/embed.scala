package views.html.base

import lila.app.templating.Environment._
import lila.app.ui.EmbedConfig
import lila.app.ui.ScalatagsTemplate._
import lila.pref.SoundSet

object embed {

  import EmbedConfig.implicits._

  def apply(title: String, cssModule: String)(body: Modifier*)(implicit config: EmbedConfig) =
    frag(
      layout.bits.doctype,
      layout.bits.htmlTag(config.lang)(
        head(
          layout.bits.charset,
          layout.bits.viewport,
          layout.bits.metaCsp(basicCsp.withNonce(config.nonce).withInlineIconFont),
          st.headTitle(title),
          layout.bits.pieceSprite(config.pieceSet),
          cssTagWithDirAndTheme(cssModule, isRTL = lila.i18n.LangList.isRTL(config.lang), config.bg)
        ),
        st.body(cls := s"base highlight ${config.board}")(
          layout.dataSoundSet := SoundSet.silent.key,
          layout.dataAssetUrl,
          layout.dataAssetVersion := assetVersion.value,
          layout.dataTheme        := config.bg,
          layout.dataPieceSet     := config.pieceSet.name,
          body
        )
      )
    )
}
