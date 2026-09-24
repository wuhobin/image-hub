$ErrorActionPreference = 'Stop'
$assetDir = 'C:/IdeaProjects/personal/image-hub/docs/design/creation-records/assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1476514525535-07fb3b4ae5f1?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'coast.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1509316785289-025f5b846b35?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'desert.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1487958449943-2429e8be8625?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'architecture.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1464822759023-fed622ff2c3b?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'mountain.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1441974231531-c6227db76b6e?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'forest.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1518837695005-2083093ee35b?auto=format&fit=max&w=1200&q=85' -OutFile (Join-Path $assetDir 'sea.jpg')
Invoke-WebRequest -Uri 'https://images.unsplash.com/photo-1515886657613-9f3515b0c78f?auto=format&fit=max&w=1000&q=85' -OutFile (Join-Path $assetDir 'fashion.jpg')
Get-ChildItem -LiteralPath $assetDir | Select-Object Name, Length