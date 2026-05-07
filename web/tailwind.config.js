/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Match the dApp Store icon's flat-2D palette.
        midnight: "#15082E",
        coingold: "#F5D670",
        coinrim: "#C99517",
        accent: "#9333EA",
        muted: "#A993D8",
      },
      fontFamily: {
        display: ["Helvetica", "Arial", "sans-serif"],
      },
    },
  },
  plugins: [],
};
