# CLAUDE.md

Visualizador de PDF para Android (Kotlin, Jetpack Compose), 100% offline. Parte da libre-apps.

## Regras

- **Idioma.** Código, comentários, commits, README e issues em **inglês**. Conversa com o
  Guilherme em português. Textos do app em `res/values*/strings.xml`: inglês (padrão), `values-pt`
  e `values-es`. Texto novo entra nos três.
- **Nada de rede.** O manifest não declara `INTERNET` nem permissão de armazenamento. Arquivo
  chega só pelo SAF (picker, "Abrir com", Compartilhar). Dependência que traga `INTERNET` para o
  manifest mesclado é recusada. Sem anúncio, sem analytics, sem conta.
- **Licença Apache-2.0.** Nada AGPL (MuPDF está fora). Toda dependência nova tem a licença
  conferida e listada no README.
- **Nome e id.** O app se chama "Open PDF Viewer" (não "OpenPDF", que é uma biblioteca Java
  conhecida). `applicationId` = `io.github.guilhermefeitosa66.openpdfviewer`; o debug ganha o
  sufixo `.debug` e o benchmark `.bench`. O id não muda depois da primeira publicação.
- **Abrir rápido é requisito.** Regressão de cold start ou de "abrir até a primeira página" é bug
  (veja Medir).

## Comandos

```bash
tools/fetch-pdfium.sh                 # uma vez: baixa o PDFium fixado (SHA-256) para pdfium/prebuilt/
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleBenchmark      # release assinado com a chave de debug, para medir
```

Nesta máquina: `memoria-livre --precisa 3` antes de Gradle (o `gradle.properties` já desliga o
daemon) e `--precisa 5` antes de subir emulador; derrube o que subir.

## Medir

- Cold start: `adb shell am start -W -n io.github.guilhermefeitosa66.openpdfviewer.bench/io.github.guilhermefeitosa66.openpdfviewer.MainActivity` (TotalTime).
- Abrir até a primeira página: o app loga `OpenPdfViewer: open to first page: N ms`.
- Referência (2026-11-24, emulador x86_64 API 35 sem GPU, build benchmark): cold start
  500 a 650 ms; primeira página 146 ms na primeira abertura, ~25 ms ao reabrir.

## Temas

- Vêm de `~/projetos/pessoal/libre-apps-themes` **copiados** para `app/src/main/assets/themes/` por
  `tools/sync-themes.py` (nunca referenciar a pasta de fora). Padrão: libre-apps seguindo o sistema.
- Regras do README dos temas: imagem de fundo só na tela inicial/recentes e atrás das barras
  (superfície a 85%); **a área do documento é sempre cor sólida**; o fundo carrega depois do
  primeiro quadro (`ThemeBackground`, `BackgroundCache`) e pode ser desligado nas configurações.
- A abertura não lê o `themes.json`: o tema escolhido fica em cache nas preferências
  (`ThemeSettings.current`, refeito quando muda o versionCode).
- A página do PDF é papel branco em qualquer tema, então o destaque de busca usa as cores de papel
  (as `searchHit` dos temas escuros são para fundo escuro); seleção = `accent` a 30%.
- Contraste: onde um token não passa AA em texto pequeno (Pastel: `accent` 3,8 e `textMuted` 3,6),
  `schemeFor` usa o token mais forte do próprio tema (`link`, `text`). Não inventar cor.

## Onde está o quê

- `docs/roadmap.md`: levantamento (Acrobat Reader, iLovePDF) e as etapas. `docs/plan.md`: motor,
  minSdk, arquitetura e orçamento de desempenho.
- `pdfium/`: módulo com a camada JNI (`src/main/cpp/pdfium_jni.cpp`) e `PdfDocument`. O PDFium não
  é thread-safe: toda chamada passa por `PdfiumThread`. Binários em `pdfium/prebuilt/` (fora do
  git), versão e hashes em `tools/fetch-pdfium.sh`; as licenças dos binários viram assets do app.
- `app/.../viewer/Viewport.kt`: geometria da rolagem contínua e do zoom (coordenadas de conteúdo,
  tela e página normalizada 0..1).
- `app/.../viewer/PageRenderer.kt`: bitmaps base (página no zoom 1, LRU pelo heap), detalhe (parte
  visível em resolução cheia quando parado com zoom > 1,1) e miniaturas.
- `app/.../viewer/TextSearch.kt`: normaliza o texto do PDFium (quebras `\r\n`, hífen de quebra
  U+0002) para busca e cópia, mantendo o mapa para os índices de caractere. Testado.
- `app/.../viewer/ViewerViewModel.kt`: abrir (senha, erros), busca, seleção.
- `app/.../data/`: recentes (`recent.json` + miniaturas em cache) e acesso via SAF (copia para o
  cache quando o provedor entrega um pipe).

## Decisões

- **Motor: PDFium** por JNI própria (justificativa em `docs/plan.md`). Edição futura (anotações,
  formulários, mesclar, dividir, girar, salvar) usa o mesmo PDFium; qpdf ou PdfBox-Android só se
  faltar algo.
- **minSdk 26**, target/compile 36. ABIs: arm64-v8a, armeabi-v7a, x86_64.
- **Dois módulos** (`:app`, `:pdfium`) em vez dos três do plano: o `:core` ficou dentro do `:app`
  até haver um segundo consumidor.
- **Handles nativos são opacos.** No arm64 os ponteiros do heap têm tag no byte alto e viram
  `jlong` negativo: nunca usar sinal de handle como erro (só `0` = falha). O emulador x86_64 não
  pega isso; mudança na camada JNI se testa também num aparelho arm64.
- **Modo da página** (normal/noturno/sépia) é global, como o tema, e é só um filtro de cor no
  desenho (rampa de luminância tinta→papel): troca na hora, sem re-renderizar. Fotos ficam
  monocromáticas nos modos noturno/sépia, de propósito.
- **Links**: internos pulam e empilham a posição anterior (chip "Voltar à página N" e o voltar do
  sistema); externos pedem confirmação e abrem no navegador (o app continua sem `INTERNET`).
- **Layout** (`Viewport` + `LayoutOptions`): contínuo vertical (padrão) ou página a página com
  snap horizontal. Página dupla só no modo página a página e só onde cabe (paisagem ou tablet,
  `smallestScreenWidthDp >= 600`); capa sozinha e direita-para-esquerda são opções. Escolha global.
- **Seleção** pode atravessar páginas (retângulos calculados para até 30 páginas; o texto copiado vai inteiro). Alça arrastada para o vão entre páginas cai na página mais próxima (`Viewport.nearestPageHit`).
- **Tema** segue o sistema; a página do PDF não é invertida no escuro (modo noite da página é v0.2).
