export const startPolling = (tick: () => Promise<boolean>, intervalMs: number): (() => void) => {
  let stopped = false
  const stop = () => {
    stopped = true
    clearInterval(timer)
  }
  const run = async () => {
    if (!stopped && !(await tick())) {
      stop()
    }
  }
  const timer = setInterval(() => void run(), intervalMs)
  void run()
  return stop
}
