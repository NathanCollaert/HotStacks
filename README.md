# Hot Stacks

A RuneLite plugin that visualizes the value of items in your bank with a heat-map overlay. Each stack displays its value beneath it, colored and ranked so you can see at a glance which items are worth the most.

## How It Works

### Value Calculation
Each stack's value = item unit price (from RuneLite's wiki price database) × quantity.

Untradeable items (quest items, etc.) show no value. Charged/degraded variants show the value of their tradeable base.

### Ranking
Items are ranked from cheapest to dearest in the current view:
- **Whole bank shown** → ranking includes all items across all tabs
- **Single tab open** → ranking includes only that tab's items
- **Bank search active** → ranking includes only matching items

Ranking is re-computed when tabs are switched or search filters change. Scrolling does not affect ranking (colors stay put).

## Tips

- Use **Heat ramp** for the most visual contrast; items are easy to rank at a glance
- Use **Two-colour gradient** to customize the look (e.g., green → red for a classic heat gradient)
- Use **Single colour** for a clean, minimal look; combine with the sparkle to mark top items without clutter
- Set **Hide below** to a higher value (e.g., 100k) to reduce clutter on low-value items
- **Scale text by value** makes expensive items pop visually but uses more font sizes; toggle it off for a cleaner look
- **Text background** is useful if other plugins or the game draw text at the bottom of slots; leave it off for a lighter overlay
- Turn off **Show stack values** and keep **Sparkle on top items** on for a subtle, unique look that only highlights your most valuable stacks