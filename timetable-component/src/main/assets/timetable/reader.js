// DOM only: no cookies, passwords, storage or network interception.
(() => {
 if (window !== window.top) return;
 const port = browser.runtime.connectNative('dapan_timetable');
 port.onMessage.addListener(message => {
  if (message.action === 'courses') { document.querySelector('#totalCourses')?.click(); return; }
  if (message.action !== 'read' || !Number.isInteger(message.request)) return;
  try {
   const snapshot = JSON.parse((() => {
  if (location.hostname !== 'jxgl.dlut.edu.cn' || !location.pathname.startsWith('/student/for-std/course-table')) return JSON.stringify({error:'请先登录并进入我的课表'});
  if (document.querySelector('input[type=password]')) return JSON.stringify({error:'请先完成学校登录'});
  const table=document.querySelector('#lessons');
  if(table) {
   const semester=document.querySelector('#semesters');
   const rows=Array.from(table.querySelectorAll('tr.lessonInfo')).filter(r=>r.getAttribute('data-semester')===semester?.value);
   if(!semester || !rows.length) return JSON.stringify({error:'课程列表尚未加载，请稍后重试'});
   const courses=rows.map(r=>{
    const cell=r.querySelector('.courseInfo');
    const name=cell?.getAttribute('data-course')||'';
    const match=name.match(/^(.*)\[([^\]]+)\]$/);
    return {code:match?.[2]||'',title:match?.[1]||'',schedule:r.cells[2]?.innerText.trim()||''};
   });
   return JSON.stringify({kind:'courses',semester:semester.value,courses});
  }
  const semester=document.querySelector('#allSemesters');
  const firstDay=document.querySelector('.columns.weekday');
  const units=Array.from(firstDay?.querySelectorAll('.blank-unit')||[]);
  if(!semester || !units.length || !document.querySelector('#startDate')) return JSON.stringify({error:'课表尚未加载，请进入我的课表后重试'});
  return JSON.stringify({kind:'grid',semester:semester.value,semesterName:semester.selectedOptions[0]?.textContent.trim(),startDate:document.querySelector('#startDate').innerText.trim(),slots:units.map(e=>({start:e.getAttribute('start-time'),end:e.getAttribute('end-time')}))});
 })());
   port.postMessage({request:message.request, snapshot});
  } catch (_) { port.postMessage({request:message.request, snapshot:{error:'学校课表尚未加载完成，请稍后重试。'}}); }
 });
})();
