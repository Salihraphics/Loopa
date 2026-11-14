select c.table_name, md5(string_agg(c.column_name||':'||c.data_type||':'||coalesce(c.column_default,'')||':'||c.is_nullable, ',' order by c.ordinal_position)) as h
from information_schema.columns c where c.table_schema='public' group by 1 order by 1
