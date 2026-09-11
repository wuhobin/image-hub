export type ImageRecord = {
  id: string
  name: string
  preview: string
  url: string
  size: number
  width: number
  height: number
  createdAt: string
  type: string
}

export type PendingImage = {
  id: string
  file: File
  preview: string
  width: number
  height: number
  status: 'ready' | 'uploading' | 'done' | 'error'
  progress: number
}

const samples = [
  ['photo-1464822759023-fed622ff2c3b', '山的另一边.jpg', 2400, 1600, 'JPG'],
  ['photo-1470770841072-f978cf4d019e', '山间来信.jpg', 2560, 1707, 'JPG'],
  ['photo-1519681393784-d120267933ba', '星夜与雪山.webp', 2400, 1600, 'WEBP'],
  ['photo-1518837695005-2083093ee35b', '海浪的形状.jpg', 2000, 1333, 'JPG'],
  ['photo-1441974231531-c6227db76b6e', '在森林里呼吸.png', 2000, 1333, 'PNG'],
  ['photo-1500530855697-b586d89ba3ee', '漫长的夏日.jpg', 2400, 1600, 'JPG'],
  ['photo-1469474968028-56623f02e42e', '向远方出发.jpg', 2400, 1600, 'JPG'],
  ['photo-1470071459604-3b5ec3a7fe05', '雾起时分.webp', 2400, 1600, 'WEBP'],
] as const

export function demoRecords(): ImageRecord[] {
  return samples.map(([photo, name, width, height, type], i) => ({
    id: `sample-${i}`, name, width, height, type,
    preview: `https://images.unsplash.com/${photo}?auto=format&fit=crop&w=1000&q=85`,
    url: `https://cdn.imagehub.example/images/sample-${i}.${type.toLowerCase()}`,
    size: 720000 + i * 280000,
    createdAt: new Date(Date.now() - i * 3 * 3600000).toISOString(),
  }))
}

