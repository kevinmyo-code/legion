package com.kevin.legion.browser

import org.json.JSONObject

/**
 * The JavaScript the browser runs in a page (ADR 0071). Each one is self-contained and returns a
 * string. Numbers are stamped on elements as `data-legion-n` by [SNAPSHOT] and cleared by the next
 * one; a new page has none, so a stale number finds nothing rather than the wrong element.
 *
 * Nothing here reads a password or card field's value, and [type] refuses a password field itself,
 * a second check behind the Kotlin one.
 */
object PageScripts {
    const val MAX_ELEMENTS = 40
    const val MAX_TEXT = 3000

    /** Numbers the visible interactive elements from the top of the viewport down; returns the page as JSON. */
    val SNAPSHOT: String = """
(function(){
var MAX=$MAX_ELEMENTS,TEXT=$MAX_TEXT;
function clean(s){return (s||'').replace(/\s+/g,' ').trim();}
function vis(e){var r=e.getBoundingClientRect();if(r.width<1||r.height<1)return false;var s=getComputedStyle(e);return s.visibility!=='hidden'&&s.display!=='none';}
function labelOf(e){var t=clean(e.getAttribute('aria-label'));
if(!t&&e.labels&&e.labels.length)t=clean(e.labels[0].innerText);
if(!t&&(e.tagName==='INPUT'||e.tagName==='TEXTAREA'))t=clean(e.placeholder)||clean(e.name);
if(!t&&e.tagName!=='INPUT'&&e.tagName!=='TEXTAREA'&&e.tagName!=='SELECT')t=clean(e.innerText);
if(!t&&e.tagName==='INPUT'&&/^(submit|button|reset)$/i.test(e.type))t=clean(e.value);
if(!t&&e.querySelector){var img=e.querySelector('img[alt]');if(img)t=clean(img.alt);}
if(!t)t=clean(e.title);return t.slice(0,80);}
document.querySelectorAll('[data-legion-n]').forEach(function(e){e.removeAttribute('data-legion-n');});
var all=document.querySelectorAll('a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[contenteditable=true]');
var out=[],n=0,more=false,pay=false;
for(var i=0;i<all.length;i++){var e=all[i];
var ac=(e.getAttribute('autocomplete')||'').toLowerCase();
var sens=/^(cc-|card)/.test(ac)||/card.?num|cvv|cvc|csc/i.test((e.name||'')+' '+(e.id||''));
if(sens)pay=true;
if(!vis(e))continue;if(e.getBoundingClientRect().bottom<0)continue;
if(n>=MAX){more=true;continue;}
n++;e.setAttribute('data-legion-n',String(n));
var tag=e.tagName,type=(e.type||'').toLowerCase(),role=e.getAttribute('role'),kind;
if(tag==='A'||role==='link')kind='link';else if(tag==='SELECT')kind='select';
else if(tag==='TEXTAREA'||e.isContentEditable)kind='field';
else if(tag==='INPUT'){if(type==='password')kind='password';else if(/^(submit|button|reset|image)$/.test(type))kind='button';else if(/^(checkbox|radio)$/.test(type))kind='checkbox';else kind='field';}
else kind='button';
var f=e.form||(e.closest?e.closest('form'):null);
var submit=(tag==='BUTTON'&&!!f&&(e.getAttribute('type')||'submit').toLowerCase()==='submit')||(tag==='INPUT'&&/^(submit|image)$/.test(type));
var search=!!f&&(f.getAttribute('role')==='search'||!!f.querySelector('input[type=search],input[name=q]'));
var o={n:n,kind:kind,label:labelOf(e),submit:submit,form:!!f,search:search,sensitive:sens||kind==='password'};
if(kind==='link'){try{o.host=new URL(e.href).host;}catch(x){}}
if((kind==='field'||kind==='select')&&!o.sensitive){o.value=clean(e.isContentEditable?e.innerText:e.value).slice(0,60);}
if(kind==='checkbox')o.checked=!!e.checked;
out.push(o);}
var text=clean(document.body?document.body.innerText:'');
return JSON.stringify({url:location.href,title:document.title,text:text.slice(0,TEXT),textCut:text.length>TEXT,elements:out,more:more,payment:pay});
})();
""".trimIndent()

    /** Clicks element [n]: "ok", or "missing" when no element carries that number. */
    fun click(n: Int): String = """
(function(){var e=document.querySelector('[data-legion-n="$n"]');if(!e)return 'missing';
e.scrollIntoView({block:'center'});if(e.focus)e.focus();e.click();return 'ok';})();
""".trimIndent()

    /**
     * Types [text] into element [n] the way a person would: the value set through the element's own
     * setter (so React-style pages see it), then input and change events. [submit] submits the field's
     * form afterwards. Returns "ok", "missing", "password" (refused) or "notfield".
     */
    fun type(n: Int, text: String, submit: Boolean): String {
        val v = JSONObject.quote(text)
        return """
(function(){var e=document.querySelector('[data-legion-n="$n"]');if(!e)return 'missing';
if((e.type||'').toLowerCase()==='password')return 'password';
if(e.isContentEditable){e.focus();e.textContent=$v;e.dispatchEvent(new Event('input',{bubbles:true}));}
else if(e.tagName==='INPUT'||e.tagName==='TEXTAREA'){var p=e.tagName==='INPUT'?HTMLInputElement.prototype:HTMLTextAreaElement.prototype;
e.focus();Object.getOwnPropertyDescriptor(p,'value').set.call(e,$v);
e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));}
else return 'notfield';
if($submit){var f=e.form;if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();}
else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));}}
return 'ok';})();
""".trimIndent()
    }

    /** Scrolls by most of a screen, or to the top; returns `{"y":..,"atTop":..,"atEnd":..}`. */
    fun scroll(direction: ScrollDirection): String {
        val move = when (direction) {
            ScrollDirection.TOP -> "window.scrollTo(0,0);"
            ScrollDirection.UP -> "window.scrollBy(0,-Math.round(window.innerHeight*0.8));"
            ScrollDirection.DOWN -> "window.scrollBy(0,Math.round(window.innerHeight*0.8));"
        }
        return """
(function(){$move var h=window.innerHeight,y=window.scrollY,max=document.documentElement.scrollHeight-h;
return JSON.stringify({y:Math.round(y),atTop:y<=0,atEnd:y>=max-2});})();
""".trimIndent()
    }
}

enum class ScrollDirection { UP, DOWN, TOP }
