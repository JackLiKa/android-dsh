#!/usr/bin/env node
/**
 * Adsh device bridge: a dependency-free MCP stdio server that forwards tool
 * calls to the on-device loopback bridge hosted by DshEngineService.
 *
 * The engine's bundled @deepseek-ai/dsh-mcp-client negotiates the 2026 era
 * first; answering `server/discover` with METHOD_NOT_FOUND makes it fall back
 * to the 2025-06-18 initialize handshake implemented below.
 */

const BASE = (process.env.ADSH_BRIDGE_URL || '').replace(/\/+$/, '')
const TOKEN = process.env.ADSH_BRIDGE_TOKEN || ''
const PROTOCOL_VERSION = '2025-06-18'

// Diagnostics go to stderr so stdout stays a clean JSON-RPC channel.
function diag(message) {
  try {
    process.stderr.write(`[adsh-device-bridge] ${message}\n`)
  } catch (_error) {
    // The engine may have closed the pipe while shutting down.
  }
}

diag(`启动，broker=${BASE || '未配置'}，token=${TOKEN ? '已配置' : '缺失'}`)

const INSTRUCTIONS = [
  '这些工具操作运行 dsh 的这台 Android 手机。能力默认关闭，只有用户在设置页明确开启后才可用。',
  '操作界面前先调用 screen_snapshot 获取当前无障碍树，再用 screen_tap 的 text 选择器或坐标操作；坐标单位为屏幕像素。',
  '通知可能包含隐私信息，只在用户要求时读取。',
  '不要执行不可逆或影响他人的操作（支付、删除、发消息、拨号等），除非用户在当前对话中明确要求或确认。',
  '自动化动作（闹钟、计时器、短信预填、拨号）会打开对应的系统应用并交由用户确认；send_sms 仅在用户明确要求发送短信时直接发送并等待发送回执，compose_sms 只预填不发送。部分系统会在 send_sms 时再弹一次发送确认，需要在手机上点“发送”，否则系统会取消该次发送。',
  '意图明确时直接调用工具：闹钟 set_alarm、计时器 set_timer、天气 get_weather、打开应用 app_open、短信 send_sms（仅明确要求发送时）。不要先解释计划或反复确认，一次工具调用能完成就只调用一次。',
  '简单问题（天气、时间、设备状态、单步操作）只给简短结论，推理链保持最短；缺少城市、联系人等关键信息时只问一个问题。',
].join('\n')

const TOOLS = [
  {
    name: 'device_info',
    title: '设备信息',
    description: '读取设备型号、Android 版本、屏幕尺寸、电量、应用版本和能力开关状态。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'notifications_list',
    title: '读取通知',
    description: '列出当前通知栏中的通知（标题、正文、应用包名、时间）。需要用户授予通知使用权。',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'integer', minimum: 1, maximum: 50, description: '最多返回多少条，默认 20。' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'screen_snapshot',
    title: '屏幕结构',
    description: '读取当前前台窗口的无障碍节点树，包含文本、描述、类名、可点击/可编辑状态和屏幕坐标。需要无障碍服务。',
    inputSchema: {
      type: 'object',
      properties: {
        maxNodes: { type: 'integer', minimum: 20, maximum: 300, description: '最多返回多少节点，默认 120。' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'screen_tap',
    title: '点击屏幕',
    description: '按文本选择器点击控件，或按屏幕像素坐标点击。文本匹配优先精确匹配，其次包含匹配；index 用于选择第几个匹配项。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要点击的控件文本或内容描述。' },
        index: { type: 'integer', minimum: 0, description: '同文本匹配项中的序号，默认 0。' },
        x: { type: 'number', description: '屏幕 X 坐标（像素）。' },
        y: { type: 'number', description: '屏幕 Y 坐标（像素）。' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'screen_swipe',
    title: '滑动屏幕',
    description: '从起点滑动到终点，坐标为屏幕像素，durationMs 默认 300。',
    inputSchema: {
      type: 'object',
      properties: {
        x1: { type: 'number' },
        y1: { type: 'number' },
        x2: { type: 'number' },
        y2: { type: 'number' },
        durationMs: { type: 'integer', minimum: 50, maximum: 3000 },
      },
      required: ['x1', 'y1', 'x2', 'y2'],
      additionalProperties: false,
    },
  },
  {
    name: 'screen_input',
    title: '输入文本',
    description: '向当前聚焦的输入框写入文本；可用 selector 按输入框的文本、描述或提示匹配。需要无障碍服务。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要写入的文本。' },
        selector: { type: 'string', description: '可选的输入框文本/描述/提示匹配。' },
      },
      required: ['text'],
      additionalProperties: false,
    },
  },
  {
    name: 'app_open',
    title: '打开应用',
    description: '按包名或应用名称启动一个已安装应用。',
    inputSchema: {
      type: 'object',
      properties: {
        package: { type: 'string', description: '应用包名，例如 com.android.settings。' },
        name: { type: 'string', description: '应用显示名称的一部分，package 为空时使用。' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'list_apps',
    title: '应用列表',
    description: '列出可启动的应用名称与包名，用于配合 app_open 精确打开应用。',
    inputSchema: {
      type: 'object',
      properties: {
        limit: { type: 'integer', minimum: 1, maximum: 200, description: '最多返回多少个应用，默认 80。' },
      },
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'set_alarm',
    title: '设置闹钟',
    description: '在系统时钟应用中创建闹钟，不需要额外权限。hour 为 0-23，minute 为 0-59。',
    inputSchema: {
      type: 'object',
      properties: {
        hour: { type: 'integer', minimum: 0, maximum: 23, description: '小时（24 小时制）。' },
        minute: { type: 'integer', minimum: 0, maximum: 59, description: '分钟。' },
        label: { type: 'string', description: '可选的闹钟标签。' },
      },
      required: ['hour', 'minute'],
      additionalProperties: false,
    },
  },
  {
    name: 'set_timer',
    title: '设置计时器',
    description: '在系统时钟应用中创建倒计时，不需要额外权限。seconds 为 1-86400。',
    inputSchema: {
      type: 'object',
      properties: {
        seconds: { type: 'integer', minimum: 1, maximum: 86400, description: '倒计时秒数。' },
        label: { type: 'string', description: '可选的计时器标签。' },
      },
      required: ['seconds'],
      additionalProperties: false,
    },
  },
  {
    name: 'compose_sms',
    title: '预填短信',
    description: '打开短信应用并预填收件人与正文，由用户手动点击发送；不会自动发送，也不需要短信权限。',
    inputSchema: {
      type: 'object',
      properties: {
        to: { type: 'string', description: '可选收件人号码。' },
        body: { type: 'string', description: '短信正文。' },
      },
      required: ['body'],
      additionalProperties: false,
    },
  },
  {
    name: 'send_sms',
    title: '发送短信',
    description:
      '直接通过系统短信服务发送短信，会真实发送且不可撤回。仅当用户在当前对话中明确要求发送，并给出收件人与正文时调用；没有明确发送指令时改用 compose_sms 预填。调用会等待最多 15 秒的发送回执（从调用开始计时，包含系统确认耗时），并返回“发送成功”或具体失败原因（含运营商错误码）。部分系统（如一加/ColorOS）会再弹一次发送确认，需在手机上点“发送”后才会发出；未确认时系统会取消该次发送。',
    inputSchema: {
      type: 'object',
      properties: {
        to: { type: 'string', description: '收件人号码。' },
        body: { type: 'string', description: '短信正文。' },
      },
      required: ['to', 'body'],
      additionalProperties: false,
    },
    annotations: { destructiveHint: true },
  },
  {
    name: 'get_weather',
    title: '查询天气',
    description:
      '直接查询指定城市当前天气与未来三天预报（Open-Meteo，无需 API Key），一次调用返回温度、体感、湿度、风、天气状况和降水概率。用户问天气时直接调用本工具，不要先做多步规划。',
    inputSchema: {
      type: 'object',
      properties: {
        city: { type: 'string', description: '城市名称，例如 北京、上海、广州、深圳；可用中文。' },
      },
      required: ['city'],
      additionalProperties: false,
    },
    annotations: { readOnlyHint: true },
  },
  {
    name: 'dial_number',
    title: '打开拨号',
    description: '打开系统拨号界面并填入号码，由用户手动拨出；不会直接呼出。',
    inputSchema: {
      type: 'object',
      properties: {
        number: { type: 'string', description: '要拨打的号码。' },
      },
      required: ['number'],
      additionalProperties: false,
    },
  },
  {
    name: 'speak_text',
    title: '朗读文本',
    description: '用系统语音合成朗读一段文本，可配合语音播报场景使用。',
    inputSchema: {
      type: 'object',
      properties: {
        text: { type: 'string', description: '要朗读的文本。' },
      },
      required: ['text'],
      additionalProperties: false,
    },
  },
  {
    name: 'stop_speaking',
    title: '停止朗读',
    description: '停止当前正在进行的系统语音朗读。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
]

let buffer = ''

process.stdin.setEncoding('utf8')
process.stdin.on('data', (chunk) => {
  buffer += chunk
  let index
  while ((index = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, index).trim()
    buffer = buffer.slice(index + 1)
    if (!line) continue
    let message
    try {
      message = JSON.parse(line)
    } catch (_error) {
      continue
    }
    void handle(message)
  }
})
process.stdin.on('end', () => process.exit(0))
process.stdin.on('error', () => process.exit(1))

function write(message) {
  process.stdout.write(`${JSON.stringify(message)}\n`)
}

function reply(id, result) {
  write({ jsonrpc: '2.0', id, result })
}

function replyError(id, code, message) {
  write({ jsonrpc: '2.0', id, error: { code, message } })
}

async function handle(message) {
  if (!message || message.jsonrpc !== '2.0' || message.id === undefined) return
  const { id, method, params } = message
  try {
    switch (method) {
      case 'initialize':
        diag('initialize（2025 协议）')
        reply(id, {
          protocolVersion: PROTOCOL_VERSION,
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'adsh-device-bridge', version: '1.0.0' },
          instructions: INSTRUCTIONS,
        })
        return
      case 'server/discover':
        // 2026-era probe: no modern support, let the client fall back to 2025.
        replyError(id, -32601, 'Method not found')
        return
      case 'ping':
        reply(id, {})
        return
      case 'tools/list':
        diag(`tools/list -> ${TOOLS.length} 个工具`)
        reply(id, { tools: TOOLS })
        return
      case 'tools/call':
        diag(`tools/call ${params?.name ?? '?'}`)
        reply(id, await callTool(params?.name, params?.arguments ?? {}))
        return
      case 'resources/list':
        reply(id, { resources: [] })
        return
      case 'prompts/list':
        reply(id, { prompts: [] })
        return
      default:
        replyError(id, -32601, `Method not found: ${method}`)
    }
  } catch (error) {
    replyError(id, -32603, errorText(error))
  }
}

function errorText(error) {
  return error instanceof Error ? error.message : String(error)
}

function textResult(text) {
  return { content: [{ type: 'text', text }] }
}

function errorResult(error) {
  return { content: [{ type: 'text', text: `设备桥调用失败：${errorText(error)}` }], isError: true }
}

function integer(value, fallback, minimum, maximum) {
  const parsed = Number.isFinite(value) ? Math.trunc(value) : fallback
  return Math.min(maximum, Math.max(minimum, parsed))
}

async function bridge(path, body) {
  if (!BASE || !TOKEN) throw new Error('设备桥未启用：请在应用设置中开启“设备能力桥”')
  const init = {
    headers: { authorization: `Bearer ${TOKEN}` },
    signal: AbortSignal.timeout(25000),
  }
  if (body !== undefined) {
    init.method = 'POST'
    init.headers['content-type'] = 'application/json'
    init.body = JSON.stringify(body)
  }
  const response = await fetch(BASE + path, init)
  const text = await response.text()
  let payload
  try {
    payload = JSON.parse(text)
  } catch (_error) {
    throw new Error(`设备桥返回了非 JSON 响应（HTTP ${response.status}）`)
  }
  if (!response.ok || payload?.ok === false) {
    throw new Error(payload?.error || `设备桥 HTTP ${response.status}`)
  }
  return payload?.value
}

async function callTool(name, args) {
  try {
    switch (name) {
      case 'device_info':
        return textResult(JSON.stringify(await bridge('/device'), null, 2))
      case 'notifications_list': {
        const limit = integer(args.limit, 20, 1, 50)
        return textResult(JSON.stringify(await bridge(`/notifications?limit=${limit}`), null, 2))
      }
      case 'screen_snapshot': {
        const maxNodes = integer(args.maxNodes, 120, 20, 300)
        const value = await bridge(`/ui?maxNodes=${maxNodes}`)
        return textResult(`${value?.screen ?? ''}\n${value?.text ?? ''}`.trim())
      }
      case 'screen_tap': {
        if (args.text === undefined && (args.x === undefined || args.y === undefined)) {
          throw new Error('需要 text，或者同时提供 x 与 y')
        }
        return textResult(await bridge('/ui/tap', args))
      }
      case 'screen_swipe':
        return textResult(await bridge('/ui/swipe', args))
      case 'screen_input':
        return textResult(await bridge('/ui/text', args))
      case 'app_open':
        return textResult(await bridge('/app/open', args))
      case 'list_apps': {
        const limit = integer(args.limit, 80, 1, 200)
        return textResult(JSON.stringify(await bridge(`/apps?limit=${limit}`), null, 2))
      }
      case 'set_alarm':
        return textResult(await bridge('/actions/alarm', args))
      case 'set_timer':
        return textResult(await bridge('/actions/timer', args))
      case 'get_weather':
        return textResult(JSON.stringify(await bridge('/actions/weather', args), null, 2))
      case 'compose_sms':
        return textResult(await bridge('/actions/sms', args))
      case 'send_sms':
        return textResult(await bridge('/actions/sms/send', args))
      case 'dial_number':
        return textResult(await bridge('/actions/dial', args))
      case 'speak_text':
        return textResult(await bridge('/actions/speak', args))
      case 'stop_speaking':
        return textResult(await bridge('/actions/speak/stop', {}))
      default:
        throw new Error(`未知工具：${name}`)
    }
  } catch (error) {
    return errorResult(error)
  }
}
