require('dotenv').config();
const express = require('express');
const mongoose = require('mongoose');
const cors = require('cors');
const bodyParser = require('body-parser');

const app = express();
const PORT = process.env.PORT || 5000;

// Middleware
app.use(cors());
app.use(bodyParser.json());

// MongoDB Connection
mongoose.connect(process.env.MONGODB_URI || 'mongodb://localhost:27017/pro_camera_db')
    .then(() => console.log('✅ MongoDB Connected'))
    .catch(err => console.error('❌ MongoDB Connection Error:', err));

// Model
const VideoMetadata = require('./models/VideoMetadata');

// Routes
app.post('/api/metadata', async (req, res) => {
    try {
        const metadata = new VideoMetadata(req.body);
        await metadata.save();
        res.status(201).json({ success: true, message: 'Metadata saved successfully', data: metadata });
    } catch (error) {
        res.status(500).json({ success: false, message: error.message });
    }
});

app.get('/api/metadata', async (req, res) => {
    try {
        const data = await VideoMetadata.find().sort({ timestamp: -1 });
        res.json(data);
    } catch (error) {
        res.status(500).json({ success: false, message: error.message });
    }
});

app.listen(PORT, () => {
    console.log(`🚀 Server running on http://localhost:${PORT}`);
});
