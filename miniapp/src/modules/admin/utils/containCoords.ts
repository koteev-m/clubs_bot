export type ContainMetrics = {
  width: number;
  height: number;
  offsetX: number;
  offsetY: number;
  scale: number;
  containerWidth: number;
  containerHeight: number;
};

export const clamp01 = (value: number): number => Math.min(1, Math.max(0, value));

export const computeContainMetrics = (
  containerWidth: number,
  containerHeight: number,
  imageWidth: number,
  imageHeight: number,
): ContainMetrics | null => {
  if (containerWidth <= 0 || containerHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
    return null;
  }
  const scale = Math.min(containerWidth / imageWidth, containerHeight / imageHeight);
  const width = imageWidth * scale;
  const height = imageHeight * scale;
  const offsetX = (containerWidth - width) / 2;
  const offsetY = (containerHeight - height) / 2;
  return {
    width,
    height,
    offsetX,
    offsetY,
    scale,
    containerWidth,
    containerHeight,
  };
};

export const clientPointToNormalized = (
  clientX: number,
  clientY: number,
  rect: DOMRect,
  metrics: ContainMetrics,
): { x: number; y: number } | null => {
  const localX = clientX - rect.left;
  const localY = clientY - rect.top;
  if (
    localX < metrics.offsetX ||
    localY < metrics.offsetY ||
    localX > metrics.offsetX + metrics.width ||
    localY > metrics.offsetY + metrics.height
  ) {
    return null;
  }
  const normalizedX = clamp01((localX - metrics.offsetX) / metrics.width);
  const normalizedY = clamp01((localY - metrics.offsetY) / metrics.height);
  return { x: normalizedX, y: normalizedY };
};
