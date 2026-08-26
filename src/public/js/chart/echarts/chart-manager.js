import { getEcharts } from '../globals.js';

const withChartDefaults = (option) => {
  const next = {
    animationDuration: 300,
    animationDurationUpdate: 300,
    ...option
  };
  // Only when a full title is present (not on partial series updates)
  if (option.title) {
    next.title = {
      ...option.title,
      textStyle: {
        fontWeight: 'bold',
        ...option.title.textStyle
      }
    };
  }
  return next;
};

export function createChartManager({ elementId, buildOption, hooks = {} }) {
  let instance = null;

  const getLegendSelected = () => {
    if (!instance) {
      return null;
    }
    const option = instance.getOption();
    return option.legend && option.legend[0]
      ? option.legend[0].selected
      : null;
  };

  return {
    ensureInit() {
      if (!instance) {
        const el = document.getElementById(elementId);
        instance = getEcharts().init(el);
        hooks.onInit?.(instance);
      }
      return instance;
    },
    initOrUpdate({ preserveLegend = false } = {}) {
      const legendSelected = preserveLegend ? getLegendSelected() : null;
      this.ensureInit();
      instance.setOption(withChartDefaults(buildOption(legendSelected)), {
        notMerge: true
      });
      instance.resize();
    },
    setOption(option, opts) {
      this.ensureInit();
      instance.setOption(withChartDefaults(option), opts);
    },
    resize() {
      instance?.resize();
    },
    getInstance: () => instance,
    getLegendSelected,
    bindAccordionResize(accordionId) {
      const el = document.getElementById(accordionId);
      if (!el) {
        return;
      }
      el.addEventListener('shown.bs.collapse', () => {
        instance?.resize();
      });
    }
  };
}
