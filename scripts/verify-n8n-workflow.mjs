#!/usr/bin/env node
// Backend workflow verification: executes only committed Code-node logic with synthetic data, never providers.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
const workflow = JSON.parse(readFileSync(new URL('../infrastructure/n8n/n8n-workflow-multicanal.json', import.meta.url), 'utf8'));
const nodes = new Map(workflow.nodes.map(node => [node.name, node]));
const tests = [];
const test = (name, run) => tests.push([name, run]);
const input = items => ({ first: () => items[0], all: () => items });
const execute = (name, items, normalized) => new Function('$input', '$', nodes.get(name).parameters.jsCode)(
  input(items), name => { assert.equal(name, 'Normalize'); return { first: () => ({ json: normalized }) }; });
const expression = (text, json, env = {}) => new Function('$json', '$env', `return (${text.slice(3, -2).trim()});`)(json, env);
const payload = {
  tipoEvento: 'NOTIFICACION_COBRANZA_MULTICANAL', canalesActivos: ['WHATSAPP', 'TELEGRAM', 'GMAIL'],
  destinatario: { nombre: 'Synthetic <patient>', telefono: '+59170012345', email: 'synthetic@example.invalid', telegramChatId: '' },
  datosFinancieros: { moneda: 'BOB', simbolo: 'Bs', monto: 350, concepto: '<script>synthetic</script>', fechaVencimiento: '2026-11-05' },
  mensajeFormateado: 'Clínica Dental: Synthetic_*[name] (Bs 350.00).',
};
const normalize = body => execute('Normalize', [{ json: { body } }])[0].json;
const normalized = normalize(payload);

test('workflow is inactive and does not retain patient execution data', () => {
  assert.equal(workflow.active, false); assert.equal(workflow.settings.executionOrder, 'v1');
  assert.equal(workflow.settings.saveDataSuccessExecution, 'none'); assert.equal(workflow.settings.saveDataErrorExecution, 'none');
  assert.equal(workflow.settings.saveManualExecutions, false); assert.deepEqual(workflow.pinData, {});
});
test('all node IDs and connections are valid and all branches are reachable', () => {
  assert.equal(new Set(workflow.nodes.map(node => node.id)).size, nodes.size);
  const visited = new Set();
  function visit(name) {
    if (visited.has(name)) return; visited.add(name); assert(nodes.has(name));
    for (const branch of workflow.connections[name]?.main ?? []) for (const edge of branch) visit(edge.node);
  }
  visit('ERP notifications'); assert.equal(visited.size, nodes.size);
});
test('webhook requires header authentication before notification dispatch', () => {
  assert.equal(nodes.get('ERP notifications').parameters.authentication, 'headerAuth');
  assert(nodes.get('ERP notifications').credentials.httpHeaderAuth);
});
test('invalid event, channels and financial input are rejected', () => {
  for (const body of [{...payload, tipoEvento:'OTHER'}, {...payload, canalesActivos:['SMS']}, {...payload, datosFinancieros:{...payload.datosFinancieros,monto:-1}}]) {
    assert.throws(() => normalize(body));
  }
});
test('each requested channel gate evaluates true and inactive channels evaluate false', () => {
  for (const channel of payload.canalesActivos) {
    const condition = nodes.get(`Enable ${channel}`).parameters.conditions.conditions[0].leftValue;
    assert.equal(expression(condition, normalized), true);
    assert.equal(expression(condition, {...normalized, canalesActivos:[]}), false);
  }
});
test('WhatsApp sends the supplied message and recipient through Meta', () => {
  const params = nodes.get('WhatsApp Meta').parameters;
  assert.equal(params.url, '=https://graph.facebook.com/v20.0/{{$env.META_PHONE_NUMBER_ID}}/messages');
  const resolvedUrl = params.url.slice(1).replace('{{$env.META_PHONE_NUMBER_ID}}', 'synthetic-phone-id');
  assert.equal(resolvedUrl, 'https://graph.facebook.com/v20.0/synthetic-phone-id/messages');
  const body = JSON.parse(expression(params.jsonBody, normalized));
  assert.equal(body.to, payload.destinatario.telefono); assert.equal(body.text.body, payload.mensajeFormateado);
  assert.equal(body.messaging_product, 'whatsapp');
});
test('Telegram uses the clinic default only when recipient chat ID is blank', () => {
  const chat = nodes.get('Telegram').parameters.chatId;
  assert.equal(expression(chat, normalized, {CHAT_ID:'synthetic-clinic-chat'}), 'synthetic-clinic-chat');
  assert.equal(expression(chat, {...normalized,destinatario:{...normalized.destinatario,telegramChatId:'-100012345'}}, {}), '-100012345');
});
test('Telegram Markdown data is escaped rather than interpreted', () => {
  const text = expression(nodes.get('Telegram').parameters.text, normalized);
  assert(text.includes('\\_')); assert(text.includes('\\*')); assert(text.includes('\\[')); assert(text.includes('Bs 350.00'));
});
test('Gmail HTML escapes patient and concept text and includes amount/date', () => {
  const html = expression(nodes.get('Gmail SMTP').parameters.html, normalized);
  assert(!html.includes('<script>')); assert(html.includes('&lt;script&gt;'));
  assert(html.includes('Synthetic &lt;patient&gt;')); assert(html.includes('Bs 350.00')); assert(html.includes('2026-11-05'));
  assert.equal(nodes.get('Gmail SMTP').parameters.subject, 'Aviso de Cobranza - Clínica Dental');
});
test('all provider failures become ERROR rather than ENVIADO', () => {
  for (const channel of payload.canalesActivos) {
    for (const failure of [{error:'Synthetic provider failure'}, {statusCode:503}]) {
      assert.equal(execute(`Result ${channel}`, [{json:failure}], normalized)[0].json.estado, 'ERROR');
    }
  }
});
test('successful provider results retain the proper provider and destination', () => {
  const expected = {WHATSAPP:'META_CLOUD_TEST_NUMBER',TELEGRAM:'TELEGRAM_BOT_API',GMAIL:'GMAIL_SMTP'};
  for (const channel of payload.canalesActivos) {
    const row = execute(`Result ${channel}`, [{json:{statusCode:200}}], normalized)[0].json;
    assert.equal(row.estado,'ENVIADO'); assert.equal(row.proveedor,expected[channel]);
    if (channel === 'TELEGRAM') assert.equal(row.destino,'Chat Clínica');
  }
});
test('joins wait for all three channel branches', () => {
  for (const name of ['Join WhatsApp Telegram','Join all channels']) {
    assert.equal(nodes.get(name).parameters.mode,'append'); assert.equal(nodes.get(name).parameters.numberInputs,2);
  }
});
test('confirmation returns 200 only when all active channels succeeded', () => {
  const items = payload.canalesActivos.map(channel => execute(`Result ${channel}`, [{json:{statusCode:200}}], normalized)[0]);
  const result = execute('Confirm channels', items)[0].json;
  assert.equal(result.exito,true); assert.equal(result.resultadosPorCanal.length,3);
  assert.equal(expression(nodes.get('Respond').parameters.options.responseCode,result),200);
  assert.deepEqual(JSON.parse(expression(nodes.get('Respond').parameters.responseBody,result)),result);
});
test('skipped channels are omitted and provider errors yield a non-success response', () => {
  const items = [execute('Result GMAIL',[{json:{error:'Synthetic failure'}}],normalized)[0],
    execute('Skip WHATSAPP',[])[0],execute('Skip TELEGRAM',[])[0]];
  const result = execute('Confirm channels',items)[0].json;
  assert.equal(result.exito,false); assert.equal(result.resultadosPorCanal.length,1);
  assert.equal(expression(nodes.get('Respond').parameters.options.responseCode,result),502);
});
for (const [name, run] of tests) { run(); console.log(`PASS ${name}`); }
console.log(`Workflow checks: ${tests.length} passed; no network or credentials used`);
