# Maquininha

O aplicativo Android que roda **dentro** da maquininha de cartão: cobra no
cartão e no Pix pela SDK da credenciadora, imprime na térmica embutida e
conversa com o servidor do [sistema de gestão](../gestao-restaurante) pela
mesma porta que o agente de impressão usa.

Não é o site embrulhado num app. As credenciadoras proíbem WebView, Cordova e
Ionic nas próprias maquininhas, e as SDKs de pagamento só existem em
Java/Kotlin — daí um projeto nativo, separado, com ciclo de homologação
próprio.

## O que ele faz hoje

- **Cobra** um valor digitado na maquininha, em crédito, débito, parcelado,
  Pix ou voucher, e mostra o andamento no visor.
- **Conta ao servidor** o resultado de toda cobrança, e insiste até conseguir:
  o que não passou fica numa fila em disco que sobrevive a queda de energia.
- **Imprime a fila da unidade** — é o agente de impressão, agora dentro da
  maquininha, sem computador no balcão.

O que ele ainda **não** faz: lançar pedido, abrir mesa, fechar conta. A
maquininha é terminal de cobrança e de impressão; o salão continua no
navegador.

## Os dois sabores

O build tem uma dimensão de flavor por credenciadora:

| flavor | o que é | precisa de |
| --- | --- | --- |
| `simulado` | maquininha de mentira: aprova, recusa, cancela e cai de propósito, e "imprime" no logcat | nada |
| `stone` | a Stone de verdade | token do PackageCloud + Stone Code |

Quase todo o desenvolvimento acontece no `simulado`, no emulador. É ele que
exercita fila de impressão, envio teimoso, telas e estreitamento de cupom sem
depender de aparelho nem de homologação.

```bash
./gradlew installSimuladoDebug
```

`rede` (a laranjinha do Itaú) entra como um terceiro flavor quando o
credenciamento sair. Nada fora de `app/src/rede/` vai mudar por causa disso —
é o que a interface `ProvedorDePagamento` compra.

## Como o aplicativo é feito

```
app/src/main/                  o aplicativo, sem uma linha de credenciadora
  pagamento/Cobranca.kt        o contrato: Cobranca, ResultadoDaCobranca, ProvedorDePagamento
  pagamento/EnvioTeimoso.kt    a fila em disco dos resultados que o servidor ainda não soube
  impressao/Impressora.kt      o contrato da térmica e a leitura do formato da fila
  impressao/AdaptadorDeLargura.kt  48 colunas do balcão → 32 da maquininha
  impressao/ServicoDaFila.kt   o serviço em primeiro plano que busca, imprime e confirma
  api/                         a conversa com o servidor, pelo token da unidade
  ui/                          a tela única: valor, forma, teclado, andamento, resultado
  Plataforma.kt                o que cada flavor precisa fornecer

app/src/simulado/              a maquininha de mentira
app/src/stone/                 a Stone (forma pronta, miolo a preencher)
```

A regra que sustenta o resto: **nada em `main/` importa SDK de credenciadora**.
Trocar de credenciadora, ou rodar duas no mesmo salão, é escrever um flavor.

## Configurar um aparelho

Na primeira abertura o aplicativo pede duas coisas, em **Ajustes**:

- **Endereço do servidor** — `https://…`, sem barra no fim.
- **Token da unidade** — o mesmo `tokenImpressao` que o agente de impressão
  usa. Ele é a credencial e o endereço ao mesmo tempo: é ele que diz de qual
  restaurante esta maquininha é.

## Para compilar

Pré-requisitos que **não** estão nesta máquina ainda: JDK 17+ e o SDK do
Android. O caminho curto é instalar o Android Studio, que traz os dois:

```bash
winget install -e --id Google.AndroidStudio
```

Depois, abra esta pasta no Android Studio uma vez — ele escreve o `sdk.dir` no
`local.properties` e gera o `gradle/wrapper/gradle-wrapper.jar`, que não está
versionado aqui.

Para o flavor `stone`, copie `local.properties.exemplo` e preencha o token do
PackageCloud e o Stone Code que a Stone manda no onboarding.

## O que ainda falta

- **A rota de cobranças no servidor.** Hoje o valor é digitado na maquininha.
  O contrato proposto está em [docs/api-maquininha.md](docs/api-maquininha.md).
- **A SDK da Stone.** `app/src/stone/` tem a forma e os pontos marcados; o
  miolo entra quando o token chegar.
- **A largura do cupom vinda do servidor.** O `AdaptadorDeLargura` é um
  remendo que faz o melhor esforço; o certo é `GET /api/impressao` aceitar a
  largura de quem está buscando.
- **Homologação.** Stone e Rede exigem antes de produção.
