import{c as s}from"./index-CwYPYTzx.js";import{C as g}from"./Card-Y34loNmr.js";import{_ as r}from"./Skeleton.vue_vue_type_script_setup_true_lang-68hqMpXd.js";import{d as f,G as o,H as v,C as a,A as i,f as n,J as c,B as d,u as h,U as w,D as b,I as k,c as u,z as t}from"./vue-vendor-BdqzwXPG.js";/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const _=s("arrow-up-down",[["path",{d:"m21 16-4 4-4-4",key:"f6ql7i"}],["path",{d:"M17 20V4",key:"1ejh1v"}],["path",{d:"m3 8 4-4 4 4",key:"11wl7u"}],["path",{d:"M7 4v16",key:"1glfcx"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const C=s("trending-down",[["path",{d:"M16 17h6v-6",key:"t6n2it"}],["path",{d:"m22 17-8.5-8.5-5 5L2 7",key:"x473p"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const B=s("trending-up",[["path",{d:"M16 7h6v6",key:"box55l"}],["path",{d:"m22 7-8.5 8.5-5-5L2 17",key:"1t1m79"}]]);/**
 * @license lucide-vue-next v0.576.0 - ISC
 *
 * This source code is licensed under the ISC license.
 * See the LICENSE file in the root directory of this source tree.
 */const q=s("wallet",[["path",{d:"M19 7V4a1 1 0 0 0-1-1H5a2 2 0 0 0 0 4h15a1 1 0 0 1 1 1v4h-3a2 2 0 0 0 0 4h3a1 1 0 0 0 1-1v-2a1 1 0 0 0-1-1",key:"18etb6"}],["path",{d:"M3 5v14a2 2 0 0 0 2 2h15a1 1 0 0 0 1-1v-4",key:"xoc0q4"}]]),M={key:0,class:"flex items-center justify-between"},V={class:"space-y-3 flex-1"},D={key:1,class:"flex items-center justify-between"},N={class:"text-xs font-semibold text-surface-500 dark:text-surface-400 tracking-wider uppercase font-mono"},T={class:"text-3xl font-bold font-mono tabular-nums tracking-tight mt-2 text-surface-900 dark:text-white group-hover:text-primary transition-colors duration-300"},z=f({__name:"StatCard",props:{label:{},value:{},icon:{},trend:{},color:{default:"primary"},loading:{type:Boolean,default:!1}},setup(e){const l=e,x=u(()=>({primary:"bg-primary/10",secondary:"bg-secondary/10",green:"bg-success/10",amber:"bg-warning/10",red:"bg-error/10"})[l.color]),y=u(()=>({primary:"text-primary",secondary:"text-secondary",green:"text-success",amber:"text-warning",red:"text-error"})[l.color]);return(m,p)=>(t(),o(g,{variant:"glass",hoverable:"",class:"group relative overflow-hidden transition-all duration-300 hover:border-primary/50 hover:shadow-glow-primary"},{default:v(()=>[p[0]||(p[0]=a("div",{class:"absolute top-0 left-0 right-0 h-[2px] bg-gradient-to-r from-transparent via-primary/40 to-transparent opacity-0 group-hover:opacity-100 transition-opacity duration-500"},null,-1)),e.loading?(t(),i("div",M,[a("div",V,[n(r,{width:"60%",height:"14px"}),n(r,{width:"45%",height:"32px"}),n(r,{width:"35%",height:"14px"})]),n(r,{variant:"circle",width:"48px",height:"48px"})])):(t(),i("div",D,[a("div",null,[a("p",N,c(e.label),1),a("p",T,c(e.value),1),e.trend?(t(),i("div",{key:0,class:d(["flex items-center mt-3 text-xs font-mono font-medium",e.trend.direction==="up"?"text-success":"text-error"])},[e.trend.direction==="up"?(t(),o(h(B),{key:0,class:"w-3.5 h-3.5 mr-1"})):(t(),o(h(C),{key:1,class:"w-3.5 h-3.5 mr-1"})),w(" "+c(e.trend.value)+"% ",1)],2)):b("",!0)]),a("div",{class:d(["p-3.5 rounded-xl border border-transparent group-hover:border-current/20 transition-all duration-300 shadow-inner group-hover:scale-105",x.value])},[(t(),o(k(e.icon),{class:d(["w-6 h-6 group-hover:scale-110 transition-transform duration-300",y.value])},null,8,["class"]))],2)]))]),_:1}))}});export{_ as A,B as T,q as W,z as _};
//# sourceMappingURL=StatCard.vue_vue_type_script_setup_true_lang-BpfL4ijg.js.map
