export type StatTile<K extends string> = {
  key: K
  label: string
  value: number
  tone?: 'danger' | 'warning'
}

type StatTilesProps<K extends string> = {
  label: string
  tiles: StatTile<K>[]
  selected: K | null
  onSelect: (key: K | null) => void
}

export const StatTiles = <K extends string>({ label, tiles, selected, onSelect }: StatTilesProps<K>) => (
  <div className="stat-tiles" role="group" aria-label={label}>
    {tiles.map((tile) => {
      const tone = tile.value === 0 ? 'zero' : tile.tone
      return (
        <button
          key={tile.key}
          type="button"
          className={`stat-tile${tone ? ` stat-tile--${tone}` : ''}`}
          aria-pressed={tile.key === selected}
          onClick={() => onSelect(tile.key === selected ? null : tile.key)}
        >
          <span className="stat-tile__value">{tile.value}</span>
          <span className="stat-tile__label">{tile.label}</span>
        </button>
      )
    })}
  </div>
)
