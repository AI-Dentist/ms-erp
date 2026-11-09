# Configurar el proveedor del agente ERP

El agente, las herramientas MCP y Text-to-SQL comparten la selección de proveedor. No se ejecuta ningún modelo local.

| Variable | Valor por defecto | Uso |
|---|---|---|
| `AI_PROVIDER` | `openai` | `openai` o `gemini`; cualquier otro valor es inválido. |
| `OPENAI_API_KEY` | vacío | Clave de OpenAI. |
| `OPENAI_MODEL` | `gpt-4o-mini` | Modelo OpenAI. |
| `GEMINI_API_KEY` | vacío | Clave de Gemini. |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Modelo Gemini. |

Configure únicamente la clave del proveedor seleccionado mediante el gestor de secretos del entorno; no la incluya en el repositorio. Gemini usa su API compatible con OpenAI en `https://generativelanguage.googleapis.com/v1beta/openai/chat/completions`.

Si la clave seleccionada está vacía, es `not-configured` o es un marcador `PEGAR_AQUI_...`, el servicio usa el planificador determinístico existente, incluso en producción. Cada respuesta del agente se identifica como `[Respaldo determinístico — sin IA]`; los reportes utilizan las plantillas determinísticas soportadas. Una clave del proveedor no seleccionado no habilita IA. Las autorizaciones y guardrails siguen aplicándose.

`ERP_AI_MODEL` conserva prioridad sobre `OPENAI_MODEL` para integraciones existentes. También se mantienen los overrides Spring `spring.ai.openai.api-key`, `spring.ai.openai.base-url` y `spring.ai.openai.chat.*`; un override explícito de chat prevalece sobre los valores seleccionados. No registre preguntas ni claves en logs.
