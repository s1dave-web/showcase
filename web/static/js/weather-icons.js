"use strict";

// Weather icons and descriptions (Open-Meteo weather codes), shared by the screensaver and the settings page.
window.ShowcaseWeather = (() => {
    const CLOUD = "M7,18.5h10.5a3.5,3.5 0,0 0,0.3,-6.99a5.5,5.5 0,0 0,-10.6,-1.2a4,4 0,0 0,-0.2,8.19z";
    const SUN = "M12,8a4,4 0,1 1,0 8a4,4 0,1 1,0 -8zM12,2.5v1.5M12,20v1.5M4.6,4.6l1.1,1.1M18.3,18.3l1.1,1.1M2.5,12h1.5M20,12h1.5M4.6,19.4l1.1,-1.1M18.3,5.7l1.1,-1.1";
    const MOON = "M20,14.5A8,8 0,1 1,9.5 4a6.2,6.2 0,0 0,10.5 10.5z";
    const small = d => `<g transform="translate(-4.5,-4.5) translate(12,12) scale(.62) translate(-12,-12)" stroke-width="2"><path d="${d}"/></g>`;
    const up = (extra) => `<g transform="translate(0,-3.5)"><path d="${CLOUD}"/></g><path d="${extra}"/>`;
    const ICONS = {
        clearDay: `<path d="${SUN}"/>`,
        clearNight: `<path d="${MOON}"/>`,
        partlyDay: small(SUN) + `<g transform="translate(1.5,1.5)"><path d="${CLOUD}"/></g>`,
        partlyNight: small(MOON) + `<g transform="translate(1.5,1.5)"><path d="${CLOUD}"/></g>`,
        cloudy: `<path d="${CLOUD}"/>`,
        fog: `<g transform="translate(0,-3)"><path d="${CLOUD}"/></g><path d="M5,19h14M7,22h10"/>`,
        rain: up("M9,18.5l-1,2.5M13,18.5l-1,2.5M17,18.5l-1,2.5"),
        snow: up("M8.5,19.5v0.01M12,21v0.01M15.5,19.5v0.01M10,22.5v0.01M14,22.5v0.01"),
        thunder: up("M13,15.5l-2.5,3.5h3l-2.5,3.5"),
    };

    function describe(code, isDay) {
        const c = Number(code);
        if (c === 0) return [isDay ? "Sunny" : "Clear", isDay ? "clearDay" : "clearNight"];
        if (c === 1) return [isDay ? "Mostly sunny" : "Mostly clear", isDay ? "clearDay" : "clearNight"];
        if (c === 2) return ["Partly cloudy", isDay ? "partlyDay" : "partlyNight"];
        if (c === 3) return ["Cloudy", "cloudy"];
        if (c === 45 || c === 48) return ["Fog", "fog"];
        if ([71, 73, 75, 77, 85, 86].includes(c)) return ["Snow", "snow"];
        if ([95, 96, 99].includes(c)) return ["Thunderstorms", "thunder"];
        if ([51, 53, 55, 56, 57].includes(c)) return ["Drizzle", "rain"];
        if ([65, 67, 82].includes(c)) return ["Heavy rain", "rain"];
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return ["Rain", "rain"];
        return ["", "cloudy"];
    }

    return { ICONS, describe };
})();
