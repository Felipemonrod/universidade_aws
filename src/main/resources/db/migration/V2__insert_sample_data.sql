-- Dados de exemplo para desenvolvimento e demonstração
INSERT INTO students (name, address, city, state, email, phone)
VALUES
    ('John Doe',       'Example Address, 100',     'Example City', 'SP', 'john.doe@example.com',   '9009009009'),
    ('Maria Silva',    'Rua das Flores, 200',       'São Paulo',    'SP', 'maria.silva@email.com',  '11999887766'),
    ('Carlos Souza',   'Av. Paulista, 1000',        'São Paulo',    'SP', 'carlos.souza@email.com', '11988776655'),
    ('Ana Oliveira',   'Rua Sete de Setembro, 50',  'Curitiba',     'PR', 'ana.oliveira@email.com', '41977665544'),
    ('Pedro Santos',   'Av. Atlântica, 300',        'Rio de Janeiro','RJ','pedro.santos@email.com', '21966554433')
ON CONFLICT (email) DO NOTHING;
