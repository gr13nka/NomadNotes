# site/assets

Files: `smooth.mp4`, `undo.mp4`, `select.mp4`, `link.mp4`, each with a matching `*.jpg`
poster (first frame).

Shoot top-down, device filling the frame, even daylight, no hands in frame except the
gesture; crop to 4:3, 1440x1080, H.264, no audio, 5-10s, trimmed so it starts on a clean
page. One idea per clip.

Suggested compression:

```
ffmpeg -i in.mov -vf "crop=…,scale=1440:-2" -an -c:v libx264 -crf 26 -preset slow -movflags +faststart smooth.mp4
```
