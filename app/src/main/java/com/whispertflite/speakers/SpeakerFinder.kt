package com.whispertflite.speakers

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig

object SpeakerFinder {
    /** One row per stretch of speech: [startMs, endMs, speaker]. numSpeakers = -1 lets it decide. */
    @JvmStatic
    fun find(segmentationModel: String, voiceModel: String, samples: FloatArray, numSpeakers: Int): Array<IntArray> {
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel),
                numThreads = 4,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = voiceModel, numThreads = 4),
            clustering = FastClusteringConfig(numClusters = numSpeakers, threshold = 0.5f),
            minDurationOn = 0.2f,
            minDurationOff = 0.5f,
        )
        val sd = OfflineSpeakerDiarization(config = config)
        try {
            val segs = sd.process(samples)
            return Array(segs.size) { i ->
                intArrayOf((segs[i].start * 1000).toInt(), (segs[i].end * 1000).toInt(), segs[i].speaker)
            }
        } finally {
            sd.release()
        }
    }
}
