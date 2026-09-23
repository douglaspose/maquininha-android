# A rota de cobranças

Contrato **proposto**. Nada disto existe no servidor ainda — está escrito aqui
para ser discutido antes de virar código lá, e porque o aplicativo já fala
exatamente esta língua (`api/Modelos.kt`).

## Por que ela precisa existir

Hoje quem digita o valor é quem está com a maquininha na mão. Isso funciona,
mas deixa dois buracos:

- **Ninguém confere.** O caixa fecha a conta de R$ 128,40 e o garçom digita
  R$ 12,84 na maquininha. O sistema não sabe, porque não foi ele quem pediu.
- **A conta não fecha sozinha.** O cartão passou, mas alguém ainda precisa
  voltar ao navegador e marcar o pagamento na mão.

Com a cobrança saindo do servidor, o valor é o da conta, e a baixa é
automática.

## Autenticação

A mesma do agente de impressão: o token da unidade no header, nunca sessão de
usuário. Uma maquininha é um serviço.

```
Authorization: Bearer <tokenImpressao da unidade>
```

## GET /api/maquininha/cobrancas

O que o caixa mandou para as maquininhas desta unidade e ainda não teve
resposta.

```json
{
  "cobrancas": [
    {
      "id": "cmu…",
      "valorEmCentavos": 12840,
      "forma": "CREDITO",
      "parcelas": 1,
      "descricao": "Mesa 7 · João"
    }
  ]
}
```

`valorEmCentavos` é inteiro de propósito: dinheiro em ponto flutuante sempre
erra um centavo em algum arredondamento, e é o tipo de erro que só aparece no
fechamento do caixa.

`forma` é o meio, não a bandeira: `CREDITO`, `CREDITO_PARCELADO`, `DEBITO`,
`PIX`, `VOUCHER`.

`descricao` é o que a maquininha mostra no visor para quem vai cobrar saber
que conta é aquela.

### Uma pergunta em aberto

Com mais de uma maquininha na unidade, duas podem pegar a mesma cobrança. Duas
saídas, e a escolha é do servidor, não do aplicativo:

1. **Cada maquininha tem token próprio**, e a cobrança nasce endereçada a uma
   delas. É mais trabalho no cadastro e resolve de vez.
2. **A cobrança é reservada por quem pegar primeiro**, com prazo curto para
   devolver. Menos cadastro, mais estados.

## POST /api/maquininha/cobrancas

O resultado. É a chamada que **não pode se perder**: uma transação aprovada
que o servidor não soube vira conta aberta no salão e cobrança dobrada no
cliente.

```json
{
  "id": "cmu…",
  "resultado": "APROVADA",
  "valorEmCentavos": 12840,
  "credenciadora": "Stone",
  "identificadorNaCredenciadora": "…",
  "bandeira": "VISA",
  "autorizacao": "123456",
  "finalDoCartao": "4321",
  "parcelas": 1
}
```

```json
{ "id": "cmu…", "resultado": "RECUSADA", "valorEmCentavos": 12840, "motivo": "Saldo insuficiente" }
```

`resultado` é `APROVADA`, `RECUSADA`, `CANCELADA` ou `ERRO`. Recusada e erro
são coisas diferentes e o servidor precisa distinguir: recusada é o emissor
dizendo não, erro é o caminho falhando — e no erro a venda **pode** ter
passado.

`finalDoCartao` são os quatro últimos dígitos. O número inteiro não trafega,
não é guardado e não aparece em log nenhum.

### Tem que ser idempotente

O aplicativo insiste. Se a rede cair depois de o servidor gravar e antes de a
resposta chegar, a mesma cobrança volta minutos depois — e pode voltar no dia
seguinte, se a maquininha passou a noite desligada. O `id` é a chave: receber
duas vezes o mesmo `id` com o mesmo resultado tem que ser igual a receber uma.

## O que o servidor precisa guardar

Além da baixa da conta, três campos que só existem aqui e que a conciliação do
dia seguinte vai pedir: `credenciadora`, `identificadorNaCredenciadora` e
`autorizacao`. São eles que amarram uma linha do extrato da adquirente a uma
conta do salão.
