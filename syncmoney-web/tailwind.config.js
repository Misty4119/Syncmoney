/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Vue Tailwind Datepicker theme colors
        "vtd-primary": "blue",
        "vtd-secondary": "gray",

        // ── Light: 天空藍系 ────────────────────────────────
        "sky-blue": {
          50:  '#EEF5FF',
          100: '#DCEAFF',
          200: '#C0D8FF',
          300: '#9CC3FF',   // 天空藍
          400: '#7FB2FF',
          500: '#6FA8FF',   // 深天空藍（Light Primary）
          600: '#5A96F0',
          700: '#3E7DE0',
          800: '#2B64C8',
          900: '#1A4BAA',
          DEFAULT: '#6FA8FF',
        },

        // ── Light: 淡紫色系 ───────────────────────────────
        "soft-purple": {
          50:  '#F7F3FF',
          100: '#EDE7FF',
          200: '#DDD1FF',
          300: '#D9B3FF',   // 淡紫色
          400: '#C9A8F0',
          500: '#B89BE8',   // 柔和紫（Light Secondary）
          600: '#A488D8',
          700: '#8E72C4',
          800: '#7558A8',
          900: '#5C4090',
          DEFAULT: '#B89BE8',
        },

        // ── Dark: 微光藍系 ────────────────────────────────
        "glow-blue": {
          50:  '#EAF0FF',
          100: '#C8D8FF',
          200: '#A0BDFF',
          300: '#709FFF',   // 微光藍（Dark Primary）
          400: '#5A8CFF',
          500: '#4A8CFF',   // 霓虹天空藍（Dark Primary Hover）
          600: '#3878F0',
          700: '#2A64D8',
          800: '#1E50BC',
          900: '#143CA0',
          DEFAULT: '#709FFF',
        },

        // ── Dark: 發光淡紫系 ──────────────────────────────
        "glow-purple": {
          50:  '#F5EEFF',
          100: '#E8D5FF',
          200: '#D6BAFF',
          300: '#C58FFF',   // 發光淡紫（Dark Secondary）
          400: '#B278F5',
          500: '#A078E6',   // 霓虹紫
          600: '#8C64D0',
          700: '#7450B8',
          800: '#5C3CA0',
          900: '#462888',
          DEFAULT: '#C58FFF',
        },

        // ── Surface — 藍灰色調 ────────────────────────────
        // (Light mode: 淡藍灰; 在 Tailwind class 中仍引用，確保相容性)
        surface: {
          50:  '#F4F7FF',
          100: '#EEF2FB',
          200: '#E0E6F6',
          300: '#C8D2EA',
          400: '#A8B2D0',
          500: '#8C95B5',
          600: '#6B7499',
          700: '#4E5880',
          800: '#353E68',
          900: '#1E2030',   // = 暗夜紫
          950: '#121520',   // = 深邃板岩
        },

        // ── Semantic ──────────────────────────────────────
        success: '#34d399',
        warning: '#fbbf24',
        error:   '#fb7185',
        info:    '#60a5fa',

        // Dynamic primary (follows CSS variable)
        primary:   'var(--color-primary)',
        secondary: 'var(--color-secondary)',
      },
      boxShadow: {
        'glow-primary': '0 0 20px var(--color-primary-glow)',
        'glow-sm':      '0 0 10px var(--color-primary-glow)',
        'glow':         '0 0 20px var(--color-primary-glow)',
        'glow-lg':      '0 0 40px var(--color-primary-glow)',
        'glow-error':   '0 0 16px rgba(251, 113, 133, 0.30)',
        'card':         '0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03)',
        'glass':        '0 8px 32px var(--glass-shadow-color)',
      },
      fontFamily: {
        sans: ['"Inter"', '"M PLUS Rounded 1c"', '"Noto Sans TC"', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'Fira Code', 'monospace'],
      },
      animation: {
        'fade-in':        'fadeIn 0.5s cubic-bezier(0.4, 0, 0.2, 1) forwards',
        'fade-slide-up':  'fadeSlideUp 0.6s cubic-bezier(0.4, 0, 0.2, 1) forwards',
        'pulse-dot':      'pulseDot 2.5s cubic-bezier(0.4, 0, 0.6, 1) infinite',
        'float':          'float 6s ease-in-out infinite',
        'float-delayed':  'float 8s ease-in-out infinite 2s',
        'shimmer':        'shimmer 2s cubic-bezier(0.4, 0, 0.2, 1) infinite',
      },
      keyframes: {
        fadeIn: {
          '0%':   { opacity: '0' },
          '100%': { opacity: '1' },
        },
        fadeSlideUp: {
          '0%':   { opacity: '0', transform: 'translateY(15px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' },
        },
        pulseDot: {
          '0%, 100%': { boxShadow: '0 0 0 0 currentColor' },
          '50%':      { boxShadow: '0 0 0 8px transparent' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%':      { transform: 'translateY(-8px)' },
        },
        shimmer: {
          '0%':   { transform: 'translateX(-100%)' },
          '100%': { transform: 'translateX(100%)' },
        },
      },
    },
  },
  plugins: [],
}
