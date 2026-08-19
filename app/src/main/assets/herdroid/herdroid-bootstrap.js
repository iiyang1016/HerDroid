(() => {
  const native = window.HerDroidNative

  const invoke = async (method, params = {}) => {
    if (!native || typeof native.invoke !== 'function') {
      throw new Error('HerDroid Android bridge is unavailable')
    }

    const raw = native.invoke(JSON.stringify({ method, params }))
    const result = JSON.parse(raw)
    if (result?.ok === false) {
      const error = new Error(result?.error?.message || `Bridge call failed: ${method}`)
      error.code = result?.error?.code
      throw error
    }
    return result
  }

  window.herdroid = Object.freeze({
    platform: 'android',
    invoke,
    bot: {
      get: () => invoke('bot.get'),
      set: enabled => invoke('bot.set', { enabled: Boolean(enabled) })
    }
  })
})()
