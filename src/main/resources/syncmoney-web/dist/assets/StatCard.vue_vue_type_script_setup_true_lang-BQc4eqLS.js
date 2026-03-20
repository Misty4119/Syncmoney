import{c as n}from"./index-CGNwwQM1.js";import{C as y}from"./Card-DWWDwQ_p.js";import{_ as r}from"./Skeleton.vue_vue_type_script_setup_true_lang-eZyaNlLY.js";import{d as f,J as o,K as g,A as i,C as a,f as s,H as c,B as d,u as h,U as v,D as w,L as k,c as u,z as t}from"./vue-vendor-DIcWDhku.js";/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const U=n("arrow-up-down",[["path",{d:"m21 16-4 4-4-4",key:"f6ql7i"}],["path",{d:"M17 20V4",key:"1ejh1v"}],["path",{d:"m3 8 4-4 4 4",key:"11wl7u"}],["path",{d:"M7 4v16",key:"1glfcx"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const b=n("trending-down",[["path",{d:"M16 17h6v-6",key:"t6n2it"}],["path",{d:"m22 17-8.5-8.5-5 5L2 7",key:"x473p"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const C=n("trending-up",[["path",{d:"M16 7h6v6",key:"box55l"}],["path",{d:"m22 7-8.5 8.5-5-5L2 17",key:"1t1m79"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const q=n("wallet",[["path",{d:"M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",key:"18etb6"}],["path",{d:"M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",key:"xoc0q4"}]]),B={key:0,class:"flex items-center justify-between"},M={class:"space-y-3 flex-1"},V={key:1,class:"flex items-center justify-between"},D={class:"text-sm text-surface-600 dark:text-surface-400 font-medium tracking-wide uppercase"},_={class:"text-3xl font-bold font-mono mt-2 text-surface-900 dark:text-white group-hover:text-primary transition-colors duration-300"},z=f({__name:"StatCard",props:{label:{},value:{},icon:{},trend:{},color:{default:"primary"},loading:{type:Boolean,default:!1}},setup(e){const l=e,p=u(()=>({primary:"bg-primary/10",secondary:"bg-secondary/10",green:"bg-success/10",amber:"bg-warning/10",red:"bg-error/10"})[l.color]),x=u(()=>({primary:"text-primary",secondary:"text-secondary",green:"text-success",amber:"text-warning",red:"text-error"})[l.color]);return(m,L)=>(t(),o(y,{variant:"glass",hoverable:"",class:"group transition-all duration-300 hover:border-primary/40 hover:shadow-glow-primary"},{default:g(()=>[e.loading?(t(),i("div",B,[a("div",M,[s(r,{width:"60%",height:"14px"}),s(r,{width:"45%",height:"32px"}),s(r,{width:"35%",height:"14px"})]),s(r,{variant:"circle",width:"48px",height:"48px"})])):(t(),i("div",V,[a("div",null,[a("p",D,c(e.label),1),a("p",_,c(e.value),1),e.trend?(t(),i("div",{key:0,class:d(["flex items-center mt-3 text-sm font-medium",e.trend.direction==="up"?"text-success":"text-error"])},[e.trend.direction==="up"?(t(),o(h(C),{key:0,class:"w-4 h-4 mr-1"})):(t(),o(h(b),{key:1,class:"w-4 h-4 mr-1"})),v(" "+c(e.trend.value)+"% ",1)],2)):w("",!0)]),a("div",{class:d(["p-3.5 rounded-xl border border-transparent group-hover:border-current transition-all duration-300 shadow-inner",p.value])},[(t(),o(k(e.icon),{class:d(["w-6 h-6 group-hover:scale-110 transition-transform duration-300",x.value])},null,8,["class"]))],2)]))]),_:1}))}});export{U as A,C as T,q as W,z as _};
//# sourceMappingURL=StatCard.vue_vue_type_script_setup_true_lang-BQc4eqLS.js.map
