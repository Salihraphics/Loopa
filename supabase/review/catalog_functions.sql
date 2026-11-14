select p.proname||'('||pg_get_function_identity_arguments(p.oid)||')' as sig, md5(pg_get_functiondef(p.oid)) as h
from pg_proc p where p.pronamespace='public'::regnamespace and p.prokind='f' order by 1
