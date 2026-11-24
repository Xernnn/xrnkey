package com.xern.xrnkey.clip

import androidx.core.content.FileProvider
import com.xern.xrnkey.R

/**
 * Serves the clipboard history's images (and nothing else: res/xml/clip_images.xml) to the field
 * one is pasted into, which commitContent grants a read of that one URI.
 */
class ClipImageProvider : FileProvider(R.xml.clip_images)
