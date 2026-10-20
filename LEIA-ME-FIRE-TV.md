# Fire OS (Fire TV com Android) — PlusTV e TxGold
O APK do Android já roda no Fire OS (leanback + banner). Gere pelo GitHub Actions "Gerar APK": campo `marca` = `plustv` (com.plustv.player) ou `txgold` (com.txgold.player, login com marca TxGold, `&brand=txgold`).
Para a Amazon Appstore: gere o APK com sua PRÓPRIA chave (veja LEIA-ME-LOJA.md; a chave inclusa é só para uso fora da loja), versionCode maior a cada envio. Ícone do TxGold: o workflow usa o ícone do painel; para ícone próprio, troque app/src/main/res/mipmap-xxhdpi/ic_launcher.png e drawable/banner.png antes de gerar.
Não testado em Fire TV real.
