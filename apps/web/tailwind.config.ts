import type { Config } from 'tailwindcss'

const config: Config = {
  darkMode: ['class'],
  content: [
    './pages/**/*.{js,ts,jsx,tsx,mdx}',
    './components/**/*.{js,ts,jsx,tsx,mdx}',
    './app/**/*.{js,ts,jsx,tsx,mdx}',
    '*.{js,ts,jsx,tsx,mdx}',
  ],
  theme: {
    extend: {
      colors: {
        background: 'hsl(var(--background))',
        foreground: 'hsl(var(--foreground))',
        surface: {
          DEFAULT: 'hsl(var(--surface))',
          raised: 'hsl(var(--surface-raised))',
          muted: 'hsl(var(--surface-muted))',
          inset: 'hsl(var(--surface-inset))',
        },
        material: {
          DEFAULT: 'hsl(var(--material-regular))',
          clear: 'hsl(var(--material-clear))',
          border: 'hsl(var(--material-border))',
        },
        separator: {
          DEFAULT: 'hsl(var(--separator))',
          strong: 'hsl(var(--separator-strong))',
        },
        tint: {
          DEFAULT: 'hsl(var(--tint))',
          muted: 'hsl(var(--tint-muted))',
          foreground: 'hsl(var(--tint-foreground))',
        },
        semantic: {
          success: 'hsl(var(--success))',
          warning: 'hsl(var(--warning))',
          danger: 'hsl(var(--danger))',
          info: 'hsl(var(--info))',
        },
        'text-primary': 'hsl(var(--text-primary))',
        'text-secondary': 'hsl(var(--text-secondary))',
        'border-subtle': 'hsl(var(--border-subtle))',
        'border-strong': 'hsl(var(--border-strong))',
        overlay: 'hsl(var(--overlay))',
        card: {
          DEFAULT: 'hsl(var(--card))',
          foreground: 'hsl(var(--card-foreground))',
        },
        popover: {
          DEFAULT: 'hsl(var(--popover))',
          foreground: 'hsl(var(--popover-foreground))',
        },
        primary: {
          DEFAULT: 'hsl(var(--primary))',
          foreground: 'hsl(var(--primary-foreground))',
        },
        secondary: {
          DEFAULT: 'hsl(var(--secondary))',
          foreground: 'hsl(var(--secondary-foreground))',
        },
        muted: {
          DEFAULT: 'hsl(var(--muted))',
          foreground: 'hsl(var(--muted-foreground))',
        },
        accent: {
          DEFAULT: 'hsl(var(--accent))',
          foreground: 'hsl(var(--accent-foreground))',
        },
        destructive: {
          DEFAULT: 'hsl(var(--destructive))',
          foreground: 'hsl(var(--destructive-foreground))',
        },
        border: 'hsl(var(--border))',
        input: 'hsl(var(--input))',
        ring: 'hsl(var(--ring))',
        chart: {
          '1': 'hsl(var(--chart-1))',
          '2': 'hsl(var(--chart-2))',
          '3': 'hsl(var(--chart-3))',
          '4': 'hsl(var(--chart-4))',
          '5': 'hsl(var(--chart-5))',
        },
        sidebar: {
          DEFAULT: 'hsl(var(--sidebar-background))',
          foreground: 'hsl(var(--sidebar-foreground))',
          primary: 'hsl(var(--sidebar-primary))',
          'primary-foreground': 'hsl(var(--sidebar-primary-foreground))',
          accent: 'hsl(var(--sidebar-accent))',
          'accent-foreground': 'hsl(var(--sidebar-accent-foreground))',
          border: 'hsl(var(--sidebar-border))',
          ring: 'hsl(var(--sidebar-ring))',
        },
      },
      borderRadius: {
        xs: 'var(--radius-xs)',
        control: 'var(--radius-control)',
        card: 'var(--radius-group)',
        surface: 'var(--radius-group)',
        sheet: 'var(--radius-sheet)',
        pill: 'var(--radius-pill)',
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      boxShadow: {
        xs: 'var(--shadow-xs)',
        sm: 'var(--shadow-sm)',
        md: 'var(--shadow-md)',
        lg: 'var(--shadow-lg)',
        material: 'var(--shadow-material)',
      },
      fontFamily: {
        sans: ['ui-sans-serif', 'system-ui', '"SF Pro Text"', '"SF Pro Display"', '"Segoe UI"', '"Noto Sans SC"', '"Microsoft YaHei UI"', '"Microsoft YaHei"', 'sans-serif'],
      },
      fontSize: {
        caption: ['var(--type-caption)', { lineHeight: '1.35' }],
        footnote: ['var(--type-footnote)', { lineHeight: '1.4' }],
        body: ['var(--type-body)', { lineHeight: '1.55' }],
        callout: ['var(--type-callout)', { lineHeight: '1.45' }],
        title3: ['var(--type-title3)', { lineHeight: '1.25' }],
        title2: ['var(--type-title2)', { lineHeight: '1.2' }],
        title1: ['var(--type-title1)', { lineHeight: '1.15' }],
        'large-title': ['var(--type-large-title)', { lineHeight: '0.98' }],
      },
      spacing: {
        'token-1': 'var(--space-1)',
        'token-2': 'var(--space-2)',
        'token-3': 'var(--space-3)',
        'token-4': 'var(--space-4)',
        'token-5': 'var(--space-5)',
        'token-6': 'var(--space-6)',
        'token-8': 'var(--space-8)',
        'token-10': 'var(--space-10)',
        'token-12': 'var(--space-12)',
      },
      transitionDuration: {
        140: '140ms',
        180: '180ms',
        220: '220ms',
      },
      keyframes: {
        'accordion-down': {
          from: {
            height: '0',
          },
          to: {
            height: 'var(--radix-accordion-content-height)',
          },
        },
        'accordion-up': {
          from: {
            height: 'var(--radix-accordion-content-height)',
          },
          to: {
            height: '0',
          },
        },
      },
      animation: {
        'accordion-down': 'accordion-down 0.2s ease-out',
        'accordion-up': 'accordion-up 0.2s ease-out',
      },
    },
  },
  plugins: [require('tailwindcss-animate'), require('@tailwindcss/typography')],
}
export default config
