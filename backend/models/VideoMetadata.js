const mongoose = require('mongoose');

const VideoMetadataSchema = new mongoose.Schema({
    filename: { type: String, required: true },
    timestamp: { type: Date, default: Date.now },
    iso: { type: Number, required: true },
    shutterSpeed: { type: String, required: true }, // e.g., "1/1000" or raw nanoseconds
    actualFps: { type: Number, required: true },
    resolution: { type: String, required: true },
    colorProfile: { type: String, default: "SDR" }
});

module.exports = mongoose.model('VideoMetadata', VideoMetadataSchema);
