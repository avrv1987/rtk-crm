export const isSameRouteClick = (clickedHref: string | null, currentHash: string): boolean => (
  clickedHref !== null && clickedHref.startsWith('#') && clickedHref === currentHash
)
