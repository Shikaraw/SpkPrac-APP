/* Audio DSP helper running on the audio thread: forwards raw mono blocks to the main thread. */
class PcmCapture extends AudioWorkletProcessor {
  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (channel && channel.length) {
      // A copy is required: the render quantum buffer is recycled after this call.
      this.port.postMessage(channel.slice(0));
    }
    return true;
  }
}
registerProcessor("pcm-capture", PcmCapture);
