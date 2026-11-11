// Pure form mapping; permission and rights decisions always remain server-side.
export const EDIT_FIELDS = ['title','category','authorName','content','summary','sourceType','sourceUrl','publisherName','sourceWorkTitle','commentLocator','rightsStatus','rightsNote','editorialRank','excerpt','hidden'];
export function fillEditor(doc, record) {
  for (const key of EDIT_FIELDS) {
    const input = doc.getElementById('edit-' + key);
    if (key === 'hidden') input.checked = record.hidden === true;
    else input.value = record[key] ?? ({ category: 'OTHER', sourceType: 'other_platform', rightsStatus: 'unverified', editorialRank: 0 }[key] ?? '');
  }
}
export function readEditor(doc) {
  const patch = {};
  for (const key of EDIT_FIELDS) {
    const input = doc.getElementById('edit-' + key);
    patch[key] = key === 'hidden' ? input.checked : key === 'editorialRank' ? Number(input.value) : input.value.trim();
  }
  if (!Number.isInteger(patch.editorialRank) || Math.abs(patch.editorialRank) > 1000000000 ||
      Array.from(patch.excerpt).length > 80 || Array.from(patch.title).length < 2 || Array.from(patch.title).length > 120 ||
      Array.from(patch.content).length > 20000 || Array.from(patch.summary).length > 240 || Array.from(patch.rightsNote).length > 2000) {
    throw new Error('EDITOR_VALIDATION');
  }
  if (patch.sourceType !== 'user_submission' || patch.sourceUrl) {
    let url; try { url = new URL(patch.sourceUrl); } catch { throw new Error('EDITOR_VALIDATION'); }
    if (url.protocol !== 'https:' || url.username || url.password) throw new Error('EDITOR_VALIDATION');
  }
  return patch;
}
